package dev.biribiri.elysium.graph;

import java.util.List;
import java.util.Optional;

/**
 * Changes to what a local account gets from an account it follows, as Mastodon's follow API takes them. An empty field is
 * left as it is, or takes its default for a new follow. These stay on this server, since other servers have no use for
 * them.
 *
 * @param reblogs   whether the account's boosts reach the follower's feeds, true by default
 * @param notifies  whether the follower is notified of the account's new statuses, false by default
 * @param languages the only languages whose statuses by the account reach the follower's feeds, where an empty list, the
 *                  default, means every language. Statuses whose language isn't known always do.
 */
public record FollowOptions(Optional<Boolean> reblogs, Optional<Boolean> notifies, Optional<List<String>> languages) {

    public static final FollowOptions NONE = new FollowOptions(Optional.empty(), Optional.empty(), Optional.empty());

    public FollowOptions {
        languages = languages.map(List::copyOf);
    }

    /**
     * The options a follow has, all given, as when they're copied to another follow.
     */
    public static FollowOptions of(boolean reblogs, boolean notify, List<String> languages) {
        return new FollowOptions(Optional.of(reblogs), Optional.of(notify), Optional.of(languages));
    }
}
