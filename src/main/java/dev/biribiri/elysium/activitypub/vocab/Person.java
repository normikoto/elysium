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
        URI followers,
        PublicKey publicKey
) {

    /**
     * @return the followers collection of a local actor
     */
    public static URI followersOf(Actor actor) {
        return URI.create(actor.uri() + "/followers");
    }

    public static Person from(Actor actor) {
        return new Person(
                ActivityStreams.ACTOR_CONTEXT,
                actor.uri(),
                "Person",
                actor.username(),
                // Browsers get the profile page from the same URL as the actor document.
                actor.uri(),
                actor.inboxUri(),
                actor.outboxUri(),
                followersOf(actor),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()));
    }
}
