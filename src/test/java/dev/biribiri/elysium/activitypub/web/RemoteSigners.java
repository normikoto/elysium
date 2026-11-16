package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentStubs;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.signing.SigningKey;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Actors on other servers that sign fetches, whose keys a spy on {@link ActivityDocuments} serves rather than fetching
 * them.
 */
final class RemoteSigners {

    private final ActorService actors;
    private final ActivityDocuments documents;
    private final JsonMapper json;

    RemoteSigners(ActorService actors, ActivityDocuments documents, JsonMapper json) {
        this.actors = actors;
        this.documents = documents;
        this.json = json;
    }

    /**
     * @param actor empty if it isn't stored here, as for most actors that only sign fetches
     */
    record Signer(URI uri, String domain, Optional<Actor> actor, PrivateKey privateKey) {

        SigningKey key() {
            return new SigningKey(uri + "#main-key", privateKey);
        }

        Actor stored() {
            return actor.orElseThrow();
        }
    }

    /**
     * A stored actor on a server of its own.
     */
    Signer remote(String username) throws Exception {
        return remoteOn(uniqueDomain(), username);
    }

    Signer remoteOn(String domain, String username) throws Exception {
        return create(domain, username, true);
    }

    /**
     * An actor that isn't stored here, on a server of its own.
     */
    Signer unstored(String username) throws Exception {
        return create(uniqueDomain(), username, false);
    }

    private Signer create(String domain, String username, boolean store) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        var uri = URI.create("https://" + domain + "/users/" + username);
        var actor = store
                ? Optional.of(actors.upsertRemote(new RemoteActorProfile(
                        uri, username, domain, URI.create(uri + "/inbox"), null, null, pem)))
                : Optional.<Actor>empty();
        var document = json.createObjectNode()
                .put("id", uri.toString())
                .put("type", "Person")
                .put("inbox", uri + "/inbox")
                .set("publicKey", json.createObjectNode()
                        .put("id", uri + "#main-key")
                        .put("owner", uri.toString())
                        .put("publicKeyPem", pem));
        DocumentStubs.actor(documents, uri, document);
        return new Signer(uri, domain, actor, pair.getPrivate());
    }

    private static String uniqueDomain() {
        return "n" + UUID.randomUUID() + ".test";
    }
}
