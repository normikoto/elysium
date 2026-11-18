package dev.biribiri.elysium.activitypub.links;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Checks whether the page each field links to links back to the profile with {@code rel="me"}, which is how someone shows
 * that a site is theirs.
 *
 * <p>Each link gets one try, and one that can't be fetched just isn't verified, as on Mastodon. Retrying would keep a
 * failed job in front of admins for a problem that's the linked site's. Checking again can wait for a profile refresh. The
 * fetches go through the federation client, which only fetches HTTPS from public addresses, so a profile can't be used
 * to reach anything on this server's network.
 */
@Configuration(proxyBeanMethods = false)
class ProfileLinksTask {

    /**
     * Sites commonly redirect to {@code www} or to a trailing slash. The client doesn't follow redirects itself, so that
     * each one is checked as a fetch of its own.
     */
    private static final int MAX_REDIRECTS = 3;

    private static final Logger log = LoggerFactory.getLogger(ProfileLinksTask.class);

    private final ActorService actors;
    private final ProfileService profiles;
    private final FederationHttpClient http;
    private final DomainBlocks blocks;
    // One remote server can change many actors' fields, even when each profile's checks are coalesced.
    private final RateLimiter remoteRequests = new RateLimiter(128, Duration.ofMinutes(1), 10_000, Ticker.systemTicker());

    ProfileLinksTask(ActorService actors, ProfileService profiles, FederationHttpClient http, DomainBlocks blocks) {
        this.actors = actors;
        this.profiles = profiles;
        this.http = http;
        this.blocks = blocks;
    }

    @Bean
    Task<ProfileLinksJob> checkProfileLinks() {
        return Jobs.task(ProfileLinks.TASK, this::run);
    }

    private CompletionHandler<ProfileLinksJob> run(ProfileLinksJob job) {
        var actor = actors.findById(job.actorId()).filter(candidate -> candidate.isActive());
        if (actor.isEmpty()) {
            return Jobs.done();
        }
        var actorUri = actor.get().uri();
        var local = actors.isLocal(actor.get());
        var remoteHost = local ? null : actorUri.getHost().toLowerCase(Locale.ROOT);
        if (!local && blocks.isBlocked(remoteHost)) {
            return Jobs.done();
        }
        var profile = profiles.find(job.actorId());
        if (!local && profile.remote() == null) {
            return Jobs.done();
        }
        var remoteUrl = profile.remote() == null ? null : profile.remote().url();
        var acceptsBacklink = local ? localBacklink(actorUri) : remoteBacklink(actorUri, remoteUrl);
        for (var i = 0; i < profile.fields().size(); i++) {
            var field = profile.fields().get(i);
            if (field.verifiedAt() != null) {
                continue;
            }
            var link = field.link();
            if (link.isPresent() && linksBack(link.get(), acceptsBacklink, remoteHost)) {
                profiles.markVerified(job.actorId(), i, field, remoteUrl, Instant.now());
            }
        }
        return Jobs.done();
    }

    private boolean linksBack(URI page, Predicate<URI> acceptsBacklink, String remoteHost) {
        var uri = page;
        try {
            for (var redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                if (remoteHost != null && remoteRequests.tryAcquire(remoteHost).isEmpty()) {
                    return false;
                }
                var response = http.get(uri, "text/html", HttpHeaders.EMPTY);
                if (response.status() >= 300 && response.status() < 400) {
                    var location = location(uri, response);
                    if (location.isEmpty()) {
                        return false;
                    }
                    uri = location.get();
                    continue;
                }
                return response.status() == 200 && hasLinkTo(uri, response.body(), acceptsBacklink);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Couldn't check {} for a profile backlink", page, e);
        }
        return false;
    }

    private static Optional<URI> location(URI from, FederationHttpClient.Response response) {
        try {
            return Optional.ofNullable(response.location()).map(from::resolve);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Relative links are resolved against the page, so a page on the same server as the profile can link back with a path.
     */
    static boolean hasLinkTo(URI page, byte[] html, Predicate<URI> acceptsBacklink) {
        var document = Jsoup.parse(new String(html, StandardCharsets.UTF_8), page.toString());
        return document.select("a[rel][href], link[rel][href]").stream()
                .filter(element -> Arrays.stream(element.attr("rel").toLowerCase(Locale.ROOT).split("\\s+"))
                        .anyMatch("me"::equals))
                .map(element -> element.absUrl("href"))
                .anyMatch(href -> acceptsHref(href, acceptsBacklink));
    }

    private static boolean acceptsHref(String href, Predicate<URI> acceptsBacklink) {
        try {
            return acceptsBacklink.test(URI.create(href));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static Predicate<URI> localBacklink(URI profile) {
        return link -> profile.getScheme().equalsIgnoreCase(link.getScheme())
                && profile.getHost().equalsIgnoreCase(link.getHost())
                && profile.getPort() == link.getPort()
                && stripSlash(profile.getPath()).equals(stripSlash(link.getPath()));
    }

    /**
     * Only the actor's origin can speak for its profile page. A document can claim any URL on a different site.
     */
    static Predicate<URI> remoteBacklink(URI actor, URI profile) {
        var useProfile = profile != null && sameOrigin(actor, profile);
        return link -> sameOrigin(actor, link) && (samePage(actor, link) || useProfile && samePage(profile, link));
    }

    private static boolean sameOrigin(URI actor, URI link) {
        return "https".equalsIgnoreCase(link.getScheme()) && link.getUserInfo() == null
                && actor.getHost().equalsIgnoreCase(link.getHost()) && httpsPort(actor) == httpsPort(link);
    }

    private static int httpsPort(URI uri) {
        return uri.getPort() == -1 ? 443 : uri.getPort();
    }

    // Another server can use case to distinguish paths or account identifiers in query parameters.
    private static boolean samePage(URI profile, URI link) {
        return stripSlash(profile.getRawPath()).equals(stripSlash(link.getRawPath()))
                && Objects.equals(profile.getRawQuery(), link.getRawQuery())
                && Objects.equals(profile.getRawFragment(), link.getRawFragment());
    }

    private static String stripSlash(String path) {
        var p = path == null ? "" : path;
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }
}
