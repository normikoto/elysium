package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Fetches ActivityPub documents from other servers, signed as the instance actor. Documents are read as plain JSON, without a
 * JSON-LD processor, and no {@code @context} is ever fetched.
 *
 * <p>Each fetch counts against {@link HostRequests}, so that fetches of actors and objects can't take every connection to a
 * host, and hold deliveries back while they run, as key fetches do.
 */
@Component
public class ActivityDocuments {

    private static final String ACCEPT = ActivityStreams.ACTIVITY_JSON + ", " + ActivityStreams.LD_JSON;
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType(ActivityStreams.ACTIVITY_JSON);
    private static final MediaType LD_JSON = MediaType.parseMediaType(ActivityStreams.ANY_LD_JSON);

    private static final Set<String> ACTOR_TYPES = Set.of("Application", "Group", "Organization", "Person", "Service");

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
     * @throws DocumentFetchException if the document can't be fetched, which is temporary if too many fetches are running
     *                                for its host
     */
    public JsonNode fetch(URI url) throws DocumentFetchException {
        // A URL without a host can't be fetched anyway, and the client says why.
        var host = url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
        if (host == null) {
            return fetchUncounted(url);
        }
        if (!hostRequests.tryStartFetch(host)) {
            throw DocumentFetchException.temporary("Too many fetches running for " + host, null);
        }
        try {
            return fetchUncounted(url);
        } finally {
            hostRequests.finishFetch(host);
        }
    }

    /**
     * Like {@link #fetch}, for {@link PublicKeyResolver}, which counts its fetches itself. Counting them here as well would
     * let each key fetch take two of a host's slots.
     */
    JsonNode fetchUncounted(URI url) throws DocumentFetchException {
        FederationHttpClient.Response response;
        try {
            response = http.get(url, ACCEPT);
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
