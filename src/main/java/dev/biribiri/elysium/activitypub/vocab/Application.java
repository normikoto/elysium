package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceActor;

import java.net.URI;
import java.util.List;

/**
 * The actor document for the server itself.
 */
public record Application(
        @JsonProperty("@context") List<String> context,
        URI id,
        String type,
        String preferredUsername,
        URI url,
        URI inbox,
        URI outbox,
        boolean manuallyApprovesFollowers,
        PublicKey publicKey
) {

    private static final List<String> CONTEXT = List.of(
            "https://www.w3.org/ns/activitystreams",
            "https://w3id.org/security/v1");

    public static Application from(InstanceActor actor) {
        return new Application(
                CONTEXT,
                actor.uri(),
                "Application",
                actor.username(),
                actor.url(),
                actor.inboxUri(),
                actor.outboxUri(),
                // Nobody should be following the server itself.
                true,
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()));
    }
}
