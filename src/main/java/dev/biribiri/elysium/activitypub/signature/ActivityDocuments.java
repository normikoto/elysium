package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Fetches ActivityPub documents from other servers, signed as the instance actor. Documents are read as plain JSON, without a
 * JSON-LD processor, and no {@code @context} is ever fetched.
 */
@Component
public class ActivityDocuments {

    private static final String ACCEPT =
            "application/activity+json, application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"";
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType("application/activity+json");
    private static final MediaType LD_JSON = MediaType.parseMediaType("application/ld+json");
    private static final String ACTIVITY_STREAMS_PROFILE = "https://www.w3.org/ns/activitystreams";

    private static final Set<String> ACTOR_TYPES = Set.of("Application", "Group", "Organization", "Person", "Service");

    private final SignedFetcher http;
    private final JsonMapper json;

    ActivityDocuments(SignedFetcher http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @return the document, which is always a JSON object
     * @throws DocumentFetchException if the document can't be fetched
     */
    public JsonNode fetch(URI url) throws DocumentFetchException {
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
        return type.equalsTypeAndSubtype(LD_JSON) && profile != null && profile.contains(ACTIVITY_STREAMS_PROFILE);
    }
}
