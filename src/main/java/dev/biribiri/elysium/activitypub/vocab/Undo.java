package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Withdraws a Follow or Like sent from here, embedding it so that its recipient doesn't have to fetch it.
 */
public record Undo<T>(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        T object
) {

    public static Undo<Follow> of(Follow follow) {
        return new Undo<>(ActivityStreams.CONTEXT, idFor(follow.id()), "Undo", follow.actor(), follow.withoutContext());
    }

    public static Undo<Like> of(Like like) {
        return new Undo<>(ActivityStreams.CONTEXT, idFor(like.id()), "Undo", like.actor(), like.withoutContext());
    }

    /**
     * Each activity is only undone once; following or liking again sends a new one.
     */
    public static String idFor(String followId) {
        return followId + "/undo";
    }
}
