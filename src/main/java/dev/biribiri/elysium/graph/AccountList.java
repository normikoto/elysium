package dev.biribiri.elysium.graph;

import java.util.Optional;
import java.util.UUID;

/**
 * A user's list of accounts they follow, which has a timeline of its own.
 *
 * @param exclusive whether its members' statuses are left out of its owner's home timeline
 */
public record AccountList(UUID id, UUID ownerId, String title, RepliesPolicy repliesPolicy, boolean exclusive) {

    /**
     * Which replies by its members the list's timeline shows, besides replies to themselves and to the list's owner, which
     * it always does.
     */
    public enum RepliesPolicy {
        /** Replies to anyone the owner follows, as on the home timeline. */
        FOLLOWED("followed"),
        /** Replies to the list's members. */
        LIST("list"),
        NONE("none");

        private final String value;

        RepliesPolicy(String value) {
            this.value = value;
        }

        /**
         * Mastodon's name for it, which is also how it's stored.
         */
        public String value() {
            return value;
        }

        public static Optional<RepliesPolicy> parse(String value) {
            for (var policy : values()) {
                if (policy.value.equals(value)) return Optional.of(policy);
            }
            return Optional.empty();
        }
    }
}
