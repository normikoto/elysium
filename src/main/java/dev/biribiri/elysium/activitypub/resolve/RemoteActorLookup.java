package dev.biribiri.elysium.activitypub.resolve;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.inbound.RemoteActors;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Finds actors on other servers for local accounts, through WebFinger or an actor's URL. What's found is stored the same way
 * as an actor that sent an activity, and refreshed by {@link RemoteActors}, which checks the document came from the actor's
 * own server. A WebFinger answer only says where to look, so it can't make this server believe anything about an actor that
 * the actor's server doesn't say.
 */
@Component
class RemoteActorLookup implements RemoteActorResolver {

    private static final Logger log = LoggerFactory.getLogger(RemoteActorLookup.class);

    /**
     * Each lookup can make a few requests to a server the caller chooses.
     */
    private static final int LOOKUPS_PER_MINUTE = 30;
    private static final int MAX_TRACKED_ACCOUNTS = 10_000;

    private static final Pattern USERNAME = Pattern.compile("[^\\s@/?#%]{1,255}");
    private static final Pattern DOMAIN = Pattern.compile("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?");
    private static final String JRD_ACCEPT = "application/jrd+json, application/json";
    private static final Set<String> ACTOR_LINK_TYPES = Set.of(
            "application/activity+json",
            "application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"");

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final ActivityDocuments documents;
    private final FederationHttpClient http;
    private final DomainBlocks blocks;
    private final InstanceProperties instance;
    private final JsonMapper json;
    private final RateLimiter lookups;

    RemoteActorLookup(
            ActorService actors,
            RemoteActors remoteActors,
            ActivityDocuments documents,
            FederationHttpClient http,
            DomainBlocks blocks,
            InstanceProperties instance,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.documents = documents;
        this.http = http;
        this.blocks = blocks;
        this.instance = instance;
        this.json = json;
        this.lookups = new RateLimiter(
                LOOKUPS_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_ACCOUNTS, Ticker.systemTicker());
    }

    @Override
    public Optional<Actor> resolveAcct(UUID requester, String username, String domain) {
        if (!USERNAME.matcher(username).matches() || !DOMAIN.matcher(domain).matches()) {
            return Optional.empty();
        }
        var normalized = domain.toLowerCase(Locale.ROOT);
        if (normalized.equals(instance.domain()) || blocks.isBlocked(hostOf(normalized))) {
            return Optional.empty();
        }
        var stored = actors.findRemoteByAcct(username, normalized);
        if (stored.filter(RemoteActors::isFresh).isPresent()) {
            return stored.filter(Actor::isActive);
        }
        countLookup(requester);
        return webFinger(username, normalized)
                .flatMap(answer -> fetch(answer.actor()))
                .filter(found -> hasAddress(found, username, normalized))
                .or(() -> stored)
                .filter(Actor::isActive);
    }

    /**
     * Whether an actor that a WebFinger answer pointed to really has the address looked up, since any server can point to
     * any actor. An actor on another domain can still have it, when its server serves WebFinger for a domain that isn't its
     * own, as Mastodon's {@code LOCAL_DOMAIN} does. Then its own server has to give the address as the actor's
     * {@code subject}.
     */
    private boolean hasAddress(Actor actor, String username, String domain) {
        if (actor.domain().equals(domain) && actor.username().equalsIgnoreCase(username)) {
            return true;
        }
        var address = "acct:" + username + "@" + domain;
        return webFinger(actor.username(), actor.domain())
                .filter(answer -> answer.actor().equals(actor.uri()) && address.equalsIgnoreCase(answer.subject()))
                .isPresent();
    }

    @Override
    public Optional<Actor> resolveUrl(UUID requester, URI url) {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || blocks.isBlocked(url.getHost())) {
            return Optional.empty();
        }
        var stored = actors.findByUri(url).filter(found -> !actors.isLocal(found));
        if (stored.filter(RemoteActors::isFresh).isPresent()) {
            return stored.filter(Actor::isActive);
        }
        if (url.getHost().equalsIgnoreCase(instance.baseUrl().getHost())) {
            return Optional.empty();
        }
        countLookup(requester);
        if (stored.isPresent()) {
            // A copy that can't be refreshed for now is still better than nothing, as for a lookup by address.
            return fetch(url).or(() -> stored).filter(Actor::isActive);
        }
        // A profile page can serve the actor at the same URL, with a different ID, which is then fetched from there.
        JsonNode document;
        try {
            document = documents.fetch(url);
        } catch (DocumentFetchException e) {
            log.debug("Couldn't look up {}: {}", url, e.getMessage());
            return Optional.empty();
        }
        if (!ActivityDocuments.isActor(document)) {
            return Optional.empty();
        }
        return parse(document.path("id").stringValue(null)).flatMap(this::fetch).filter(Actor::isActive);
    }

    private void countLookup(UUID requester) {
        if (lookups.tryAcquire(requester.toString()).isEmpty()) {
            throw new ResolveLimitException();
        }
    }

    /**
     * @return the actor's ID, from the first link to an ActivityPub document, and the address the answer gives for it
     */
    private Optional<WebFingerAnswer> webFinger(String username, String domain) {
        var url = UriComponentsBuilder.fromUriString("https://" + domain)
                .path("/.well-known/webfinger")
                .queryParam("resource", "acct:" + username + "@" + domain)
                .encode()
                .build()
                .toUri();
        FederationHttpClient.Response response;
        try {
            response = http.get(url, JRD_ACCEPT, new HttpHeaders());
        } catch (IOException e) {
            log.debug("Couldn't look up {}@{}: {}", username, domain, e.getMessage());
            return Optional.empty();
        }
        if (response.status() != 200) {
            log.debug("Looking up {}@{} returned {}", username, domain, response.status());
            return Optional.empty();
        }
        JsonNode descriptor;
        try {
            descriptor = json.readTree(response.body());
        } catch (JacksonException e) {
            return Optional.empty();
        }
        var subject = descriptor.path("subject").stringValue(null);
        return descriptor.path("links").valueStream()
                .filter(link -> "self".equals(link.path("rel").stringValue(null)))
                .filter(link -> ACTOR_LINK_TYPES.contains(link.path("type").stringValue("")))
                .map(link -> link.path("href").stringValue(null))
                .flatMap(href -> parse(href).stream())
                .findFirst()
                .map(actor -> new WebFingerAnswer(subject, actor));
    }

    /**
     * @return the stored actor, or empty if it can't be fetched or stored
     */
    private Optional<Actor> fetch(URI actorUri) {
        if (blocks.isBlocked(actorUri.getHost())) {
            return Optional.empty();
        }
        try {
            return Optional.of(remoteActors.fetchOrRefresh(actorUri));
        } catch (RuntimeException e) {
            // RemoteActors says why with a PermanentJobFailure or another exception, and either way there's nothing to show.
            log.debug("Couldn't fetch {}: {}", actorUri, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<URI> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            var uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
        } catch (java.net.URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * @param subject the address the answer gives, if any
     */
    private record WebFingerAnswer(String subject, URI actor) {
    }

    private static String hostOf(String domain) {
        var colon = domain.indexOf(':');
        return colon < 0 ? domain : domain.substring(0, colon);
    }
}
