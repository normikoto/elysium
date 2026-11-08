package dev.biribiri.elysium.content;

/** Locally known boosts and whether the current viewer has boosted the post. */
public record BoostStats(long count, boolean reblogged) {
    public static final BoostStats EMPTY = new BoostStats(0, false);
}
