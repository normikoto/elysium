package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;

import java.net.URI;
import java.util.List;

/**
 * An ActivityPub actor document.
 */
public record Person(
        @JsonProperty("@context") List<String> context,
        URI id,
        String type,
        String preferredUsername,
        URI url,
        URI inbox,
        URI outbox,
        PublicKey publicKey
) {

    private static final List<String> CONTEXT = List.of(
            "https://www.w3.org/ns/activitystreams",
            "https://w3id.org/security/v1");

    public static Person from(Actor actor) {
        return new Person(
                CONTEXT,
                actor.uri(),
                "Person",
                actor.username(),
                // Browsers get the profile page from the same URL as the actor document.
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()));
    }
}
