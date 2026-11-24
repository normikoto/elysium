package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.actor.Actor;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fetches ActivityPub documents from other servers, signed as the instance actor unless a caller names a local actor. Documents are read as plain JSON, without a
 * JSON-LD processor, and no {@code @context} is ever fetched.
 *
 * <p>Each fetch counts against {@link HostRequests}, so that fetches of actors and objects can't take every connection to a
 * host, and hold deliveries back while they run, as key fetches do.
 *
 * <p>A fetch that finds no room fails straight away, unless it's part of a {@link #lookUp}. A job that waited here would
 * hold one of the scheduler's few threads while it could be running jobs for other hosts, so {@code Jobs} has it wait in
 * the queue instead.
 */
@Component
public class ActivityDocuments {

    private static final String ACCEPT = ActivityStreams.ACTIVITY_JSON + ", " + ActivityStreams.LD_JSON;
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType(ActivityStreams.ACTIVITY_JSON);
    private static final MediaType LD_JSON = MediaType.parseMediaType(ActivityStreams.ANY_LD_JSON);

    private static final Set<String> ACTOR_TYPES = Set.of("Application", "Group", "Organization", "Person", "Service");

    /**
     * How long a {@link #lookUp} can spend waiting for room, in all, across however many fetches it makes. Long enough for
     * a running fetch to finish, and short enough that a person searching gets an answer.
     */
    static final Duration LOOKUP_WAIT = Duration.ofSeconds(2);
    private static final Duration POLL = Duration.ofMillis(50);
    private static final ScopedValue<Instant> WAIT_FOR_ROOM_UNTIL = ScopedValue.newInstance();

    private final SignedFetcher http;
    private final JsonMapper json;
    private final HostRequests hostRequests;

    ActivityDocuments(SignedFetcher http, JsonMapper json, HostRequests hostRequests) {
        this.http = http;
        this.json = json;
        this.hostRequests = hostRequests;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @return the document, which is always a JSON object
     * @throws DocumentFetchException if the document can't be fetched
     * @throws HostBusyException      if too many fetches are running for its host, which isn't a failure of its server
     */
    public JsonNode fetch(URI url) throws DocumentFetchException {
        return fetchCounted(url, Optional.empty());
    }

    /**
     * Like {@link #fetch}, signed as a local actor, for a document that its server only shows to some actors, such as
     * followers-only statuses for the actor's followers. Only the signer counts on servers that check who's asking, as on
     * this one, so the caller has to choose an actor that's allowed to see what it's after.
     */
    public JsonNode fetchAs(URI url, Actor signer) throws DocumentFetchException {
        return fetchCounted(url, Optional.of(signer));
    }

    private JsonNode fetchCounted(URI url, Optional<Actor> signer) throws DocumentFetchException {
        // A URL without a host can't be fetched anyway, and the client says why.
        if (url.getHost() == null) {
            return read(url, signer);
        }
        var host = url.getHost().toLowerCase(Locale.ROOT);
        if (!hostRequests.tryStartFetch(host) && !waitForRoom(host)) {
            throw new HostBusyException(host);
        }
        try {
            return read(url, signer);
        } finally {
            hostRequests.finishFetch(host);
        }
    }

    /**
     * Runs a lookup that someone is waiting on, such as a search, during which a fetch from a busy host waits up to
     * {@link #LOOKUP_WAIT} for room. Otherwise a search for an account on a server that's busy with this server's jobs
     * would find nothing.
     */
    public static <T> T lookUp(Supplier<T> lookup) {
        // Such as mentions in a scheduled post, whose job can wait in the queue instead.
        if (WAIT_FOR_ROOM_UNTIL.isBound() || Jobs.isJobThread()) {
            return lookup.get();
        }
        return ScopedValue.where(WAIT_FOR_ROOM_UNTIL, Instant.now().plus(LOOKUP_WAIT)).call(lookup::get);
    }

    /**
     * Polls, since {@link HostRequests} doesn't say when a slot comes free. Lookups are rare enough for that not to matter.
     *
     * @return whether a fetch was started, which then needs {@link HostRequests#finishFetch}
     */
    private boolean waitForRoom(String host) {
        if (!WAIT_FOR_ROOM_UNTIL.isBound()) {
            return false;
        }
        var until = WAIT_FOR_ROOM_UNTIL.get();
        while (Instant.now().isBefore(until)) {
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (hostRequests.tryStartFetch(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Like {@link #fetch}, for {@link PublicKeyResolver}, which counts its fetches itself. Counting them here as well would
     * let each key fetch take two of a host's slots.
     */
    JsonNode fetchUncounted(URI url) throws DocumentFetchException {
        return read(url, Optional.empty());
    }

    /**
     * @param signer the local actor to sign as, or empty for the instance actor
     */
    private JsonNode read(URI url, Optional<Actor> signer) throws DocumentFetchException {
        FederationHttpClient.Response response;
        try {
            response = signer.isPresent() ? http.getAs(url, ACCEPT, signer.get()) : http.get(url, ACCEPT);
        } catch (BlockedTargetException | ResponseTooLargeException e) {
            throw DocumentFetchException.permanent("Won't fetch " + url + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw DocumentFetchException.temporary("Couldn't fetch " + url + ": " + e.getMessage(), e);
        }
        var status = response.status();
        if (status >= 500 || status == 408 || status == 429) {
            throw DocumentFetchException.temporary("Fetching " + url + " returned " + status, null);
        }
        if (status == 404 || status == 410) {
            throw DocumentFetchException.gone("Fetching " + url + " returned " + status);
        }
        if (status != 200) {
            throw DocumentFetchException.permanent("Fetching " + url + " returned " + status, null);
        }
        if (!isActivityPubType(response.contentType())) {
            // Otherwise a JSON file uploaded to a shared host could pass as that host's actor.
            throw DocumentFetchException.permanent("Fetching " + url + " returned the wrong content type", null);
        }
        JsonNode document;
        try {
            document = json.readTree(response.body());
        } catch (JacksonException e) {
            throw DocumentFetchException.permanent("Fetching " + url + " returned invalid JSON", e);
        }
        if (!document.isObject()) {
            throw DocumentFetchException.permanent("Fetching " + url + " didn't return a JSON object", null);
        }
        return document;
    }

    /**
     * @return whether a document's {@code type} is one of the actor types, or includes one
     */
    public static boolean isActor(JsonNode document) {
        var type = document.path("type");
        return type.isArray() ? type.valueStream().anyMatch(ActivityDocuments::isActorType) : isActorType(type);
    }

    private static boolean isActorType(JsonNode type) {
        return type.isString() && ACTOR_TYPES.contains(type.stringValue());
    }

    private static boolean isActivityPubType(String contentType) {
        if (contentType == null) {
            return false;
        }
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        if (type.equalsTypeAndSubtype(ACTIVITY_JSON)) {
            return true;
        }
        var profile = type.getParameter("profile");
        return type.equalsTypeAndSubtype(LD_JSON) && profile != null && profile.contains(ActivityStreams.CONTEXT);
    }
}
