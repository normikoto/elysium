package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Withdraws a {@code Follow}, {@code Like}, {@code Announce} or {@code Block} sent from here, which includes it so that the other server doesn't have to look
 * it up. Some servers don't keep the ID of a {@code Follow}, and match on its actors instead.
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

    public static Undo<Announce> of(Announce announce) {
        return new Undo<>(ActivityStreams.CONTEXT, idFor(announce.id()), "Undo", announce.actor(),
                announce.withoutContext());
    }

    public static Undo<Block> of(Block block) {
        return new Undo<>(ActivityStreams.CONTEXT, idFor(block.id()), "Undo", block.actor(), block.withoutContext());
    }

    /**
     * Each activity is only undone once; following, liking, boosting or blocking again sends a new one.
     */
    public static String idFor(String activityId) {
        return activityId + "/undo";
    }
}
