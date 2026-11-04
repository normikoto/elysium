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
import java.net.URISyntaxException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fetches a key from the server that a key ID points to, and confirms which actor it belongs to. Documents are read as
 * plain JSON, without a JSON-LD processor, and no {@code @context} is ever fetched.
 *
 * <p>A key ID can point to one of three things:
 *
 * <ul>
 * <li>An actor document that has the key in {@code publicKey}, usually as {@code {actor}#main-key}. Nothing further is
 * needed, because the actor is speaking for itself.
 * <li>A separate key document with an {@code owner}. Anyone could claim to own a key, so the owner's actor document also
 * has to list it.
 * <li>A stub of an actor, whose {@code id} is the actor's own URL rather than the one it was fetched from. GoToSocial
 * serves these. Anyone who can serve a document on a shared host could write a stub, so nothing in it is used except
 * where to look next, and the actor's real document has to list the key.
 * </ul>
 *
 * <p>Requests are signed as the instance actor, because some servers won't serve even an actor document without that.
 */
@Component
class KeyDocuments {

    private static final String ACCEPT =
            "application/activity+json, application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\"";
    private static final MediaType ACTIVITY_JSON = MediaType.parseMediaType("application/activity+json");
    private static final MediaType LD_JSON = MediaType.parseMediaType("application/ld+json");
    private static final String ACTIVITY_STREAMS_PROFILE = "https://www.w3.org/ns/activitystreams";

    private static final Set<String> ACTOR_TYPES = Set.of("Application", "Group", "Organization", "Person", "Service");

    private final SignedFetcher http;
    private final JsonMapper json;

    KeyDocuments(SignedFetcher http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @throws KeyFetchException if the key can't be fetched or confirmed
     */
    ResolvedKey fetch(KeyId keyId) throws KeyFetchException {
        var document = fetchJson(keyId.document());
        if (!hasId(document, keyId.document())) {
            return fromStub(keyId, document);
        }
        if (!document.path("publicKey").isMissingNode()) {
            return fromActor(keyId, document);
        }
        if (document.path("publicKeyPem").isString()) {
            return fromKeyDocument(keyId, document);
        }
        throw permanent("Document for " + keyId.value() + " doesn't have a public key");
    }

    /**
     * Only follows one step, so that a server can't send this on a long chain of fetches.
     */
    private ResolvedKey fromStub(KeyId keyId, JsonNode stub) throws KeyFetchException {
        var id = stub.path("id").stringValue(null);
        if (id == null) {
            throw permanent("Document at " + keyId.document() + " doesn't have an id");
        }
        return fromActor(keyId, fetchDocument(sameServerUri("Actor", id, keyId)));
    }

    private static ResolvedKey fromActor(KeyId keyId, JsonNode actor) throws KeyFetchException {
        requireActor(actor);
        var id = actor.path("id").asString();
        var entries = entries(actor);
        var entry = entries.stream()
                .filter(candidate -> keyId.value().equals(entryId(candidate)))
                .findFirst()
                // Some servers use the actor's own ID as the key ID, when an actor only has one key.
                .orElse(!keyId.hasFragment() && keyId.value().equals(id) && entries.size() == 1
                        ? entries.getFirst()
                        : null);
        if (entry == null || !entry.isObject() || !entry.path("publicKeyPem").isString()) {
            throw permanent("Actor doesn't have the key " + keyId.value());
        }
        if (!entry.path("owner").isMissingNode() && !id.equals(ownerId(entry))) {
            throw permanent("Key " + keyId.value() + " in an actor document has a different owner");
        }
        return new ResolvedKey(keyId.value(), URI.create(id), parseKey(entry));
    }

    private ResolvedKey fromKeyDocument(KeyId keyId, JsonNode keyDocument) throws KeyFetchException {
        if (keyId.hasFragment()) {
            throw permanent("Key ID " + keyId.value() + " has a fragment, but its document is a key");
        }
        var key = parseKey(keyDocument);
        var ownerId = ownerId(keyDocument);
        if (ownerId == null) {
            throw permanent("Key " + keyId.value() + " doesn't have an owner");
        }
        var owner = sameServerUri("Owner", ownerId, keyId);

        var actor = fetchDocument(owner);
        requireActor(actor);
        for (var entry : entries(actor)) {
            if (keyId.value().equals(entryId(entry))) {
                // An actor can just refer to the key, but if it also includes one, then the two have to agree.
                if (entry.path("publicKeyPem").isString() && !parseKey(entry).equals(key)) {
                    throw permanent("Actor has a different key for " + keyId.value());
                }
                return new ResolvedKey(keyId.value(), owner, key);
            }
        }
        throw permanent("Owner of " + keyId.value() + " doesn't list the key");
    }

    private JsonNode fetchDocument(URI url) throws KeyFetchException {
        var document = fetchJson(url);
        // Otherwise a document could describe an actor somewhere other than where it was fetched from.
        if (!hasId(document, url)) {
            throw permanent("Document at " + url + " has a different id");
        }
        return document;
    }

    private static boolean hasId(JsonNode document, URI url) {
        return url.toString().equals(document.path("id").stringValue(null));
    }

    private JsonNode fetchJson(URI url) throws KeyFetchException {
        FederationHttpClient.Response response;
        try {
            response = http.get(url, ACCEPT);
        } catch (BlockedTargetException | ResponseTooLargeException e) {
            throw KeyFetchException.permanent("Won't fetch " + url + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw KeyFetchException.temporary("Couldn't fetch " + url + ": " + e.getMessage(), e);
        }
        var status = response.status();
        if (status >= 500 || status == 408 || status == 429) {
            throw KeyFetchException.temporary("Fetching " + url + " returned " + status, null);
        }
        if (status == 404 || status == 410) {
            throw KeyFetchException.gone("Fetching " + url + " returned " + status);
        }
        if (status != 200) {
            throw permanent("Fetching " + url + " returned " + status);
        }
        if (!isActivityPubType(response.contentType())) {
            // Otherwise a JSON file uploaded to a shared host could pass as that host's actor.
            throw permanent("Fetching " + url + " returned the wrong content type");
        }
        JsonNode document;
        try {
            document = json.readTree(response.body());
        } catch (JacksonException e) {
            throw KeyFetchException.permanent("Fetching " + url + " returned invalid JSON", e);
        }
        if (!document.isObject()) {
            throw permanent("Fetching " + url + " didn't return a JSON object");
        }
        return document;
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

    private static void requireActor(JsonNode document) throws KeyFetchException {
        var type = document.path("type");
        var isActor = type.isArray()
                ? type.valueStream().anyMatch(KeyDocuments::isActorType)
                : isActorType(type);
        if (!isActor) {
            throw permanent("Document " + document.path("id").asString() + " isn't an actor");
        }
    }

    private static boolean isActorType(JsonNode type) {
        return type.isString() && ACTOR_TYPES.contains(type.stringValue());
    }

    /**
     * @return the actor's keys, which can be one key or several, and which can each be an object or just an ID
     */
    private static List<JsonNode> entries(JsonNode actor) {
        var publicKey = actor.path("publicKey");
        var entries = new ArrayList<JsonNode>();
        if (publicKey.isArray()) {
            publicKey.forEach(entries::add);
        } else if (publicKey.isObject() || publicKey.isString()) {
            entries.add(publicKey);
        }
        return entries;
    }

    private static String entryId(JsonNode entry) {
        return entry.isString() ? entry.stringValue() : entry.path("id").stringValue(null);
    }

    /**
     * @return the ID of a key's owner, which can be an ID or an object, or null if it doesn't have one
     */
    private static String ownerId(JsonNode key) {
        var owner = key.path("owner");
        return owner.isObject() ? owner.path("id").stringValue(null) : owner.stringValue(null);
    }

    private static PublicKey parseKey(JsonNode holder) throws KeyFetchException {
        try {
            return PublicKeyPem.parse(holder.path("publicKeyPem").stringValue());
        } catch (IllegalArgumentException e) {
            throw KeyFetchException.permanent(e.getMessage(), e);
        }
    }

    /**
     * Actors have to be on the same server as their keys, so that one server can't claim the keys of another.
     *
     * @param role what the URI is for, to explain a failure
     */
    private static URI sameServerUri(String role, String value, KeyId keyId) throws KeyFetchException {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw KeyFetchException.permanent(role + " of " + keyId.value() + " isn't a valid URI", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !KeyId.authority(uri.getScheme(), uri.getHost(), uri.getPort()).equals(keyId.authority())) {
            throw permanent(role + " of " + keyId.value() + " is on another server");
        }
        // The HTTP client refuses user info with an error that looks like a network failure, which could be tried again.
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw permanent(role + " of " + keyId.value() + " isn't the URL of a document");
        }
        return uri;
    }

    private static KeyFetchException permanent(String message) {
        return KeyFetchException.permanent(message, null);
    }
}
