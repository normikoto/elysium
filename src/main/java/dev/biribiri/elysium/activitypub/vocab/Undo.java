package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Withdraws a {@code Follow} sent from here, which includes the {@code Follow} so that the other server doesn't have to look
 * it up. Some servers don't keep the ID of a {@code Follow}, and match on its actors instead.
 */
public record Undo(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        Follow object
) {

    public static Undo of(Follow follow) {
        return new Undo(ActivityStreams.CONTEXT, idFor(follow.id()), "Undo", follow.actor(), follow.withoutContext());
    }

    /**
     * Each {@code Follow} is only undone once, because following again sends a new one.
     */
    public static String idFor(String followId) {
        return followId + "/undo";
    }
}
