package dev.biribiri.elysium.content;

/** Locally known likes and whether the current viewer has liked the post. */
public record LikeStats(long count, boolean favourited) {
    public static final LikeStats EMPTY = new LikeStats(0, false);
}
