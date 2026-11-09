package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.activitypub.signature.Digests;

import java.net.URI;

/**
 * A refusal of a {@code Follow}, which includes the {@code Follow} as an {@link Accept} does.
 */
public record Reject(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        URI actor,
        Accept.Follow object
) {

    /**
     * @param followed the local actor who was asked, who sends this
     * @param followId the {@code id} of the {@code Follow}, which is hashed because it came from another server
     * @param follower the actor who sent the {@code Follow}
     */
    public static Reject of(URI followed, String followId, URI follower) {
        return new Reject(
                ActivityStreams.CONTEXT,
                URI.create(followed + "#rejects/follows/" + Digests.sha256Hex(followId)),
                "Reject",
                followed,
                new Accept.Follow(followId, "Follow", follower, followed));
    }
}
