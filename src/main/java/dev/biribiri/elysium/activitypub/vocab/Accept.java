package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.activitypub.signature.Digests;

import java.net.URI;

/**
 * An answer to a {@code Follow}, which includes the {@code Follow} so that the other server doesn't have to look it up.
 */
public record Accept(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        URI actor,
        Follow object
) {

    /**
     * The {@code id} comes from the {@code Follow}'s, rather than from the two actors. Queuing an activity ID again keeps
     * the body it already has, so an ID from the actors would answer a new {@code Follow} by naming the old one.
     *
     * @param followed the local actor being followed, who sends this
     * @param followId the {@code id} of the {@code Follow}, which is hashed because it came from another server
     * @param follower the actor who sent the {@code Follow}
     */
    public static Accept of(URI followed, String followId, URI follower) {
        return new Accept(
                ActivityStreams.CONTEXT,
                URI.create(idFor(followed, followId)),
                "Accept",
                followed,
                new Follow(followId, "Follow", follower, followed));
    }

    /**
     * @return the {@code id} of the answer to a {@code Follow}
     */
    public static String idFor(URI followed, String followId) {
        return followed + "#accepts/follows/" + Digests.sha256Hex(followId);
    }

    public record Follow(String id, String type, URI actor, URI object) {
    }
}
