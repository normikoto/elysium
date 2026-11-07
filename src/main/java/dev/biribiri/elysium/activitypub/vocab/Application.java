package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceActor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.List;

/**
 * The actor document for the server itself.
 */
@Schema(name = "ApplicationActor", description = "The ActivityPub actor document for the server itself.")
public record Application(
        @JsonProperty("@context") List<String> context,
        URI id,
        String type,
        String preferredUsername,
        URI url,
        URI inbox,
        URI outbox,
        boolean manuallyApprovesFollowers,
        Endpoints endpoints,
        PublicKey publicKey
) {

    /**
     * @param sharedInbox this server's shared inbox
     */
    public static Application from(InstanceActor actor, URI sharedInbox) {
        return new Application(
                ActivityStreams.ACTOR_CONTEXT,
                actor.uri(),
                "Application",
                actor.username(),
                actor.url(),
                actor.inboxUri(),
                actor.outboxUri(),
                // Nobody should be following the server itself.
                true,
                new Endpoints(sharedInbox),
                new PublicKey(actor.publicKeyId(), actor.uri(), actor.publicKeyPem()));
    }
}
