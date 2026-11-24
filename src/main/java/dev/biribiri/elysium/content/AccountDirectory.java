package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.DirectoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounts that choose to be listed, as Mastodon's profile directory lists them. It's here rather than with the
 * profiles because the accounts that posted last come first, which only statuses can say.
 */
@Service
public class AccountDirectory {

    public enum Order {
        /**
         * Those that posted most recently first, and those that never have last. A status counts here as it does for an
         * account's {@code last_status_at}, so the order agrees with what apps show.
         */
        ACTIVE,
        /**
         * Those that joined this server, or that this server first heard of, most recently first.
         */
        NEW;

        /**
         * Reads Mastodon's names for the orders, which are also the web page's.
         *
         * @param value {@code active}, which is what a missing or empty one means, or {@code new}
         * @return empty for any other value
         */
        public static Optional<Order> parse(String value) {
            if (value == null) {
                return Optional.of(ACTIVE);
            }
            return switch (value) {
                case "", "active" -> Optional.of(ACTIVE);
                case "new" -> Optional.of(NEW);
                default -> Optional.empty();
            };
        }
    }

    private final DirectoryRepository directory;

    AccountDirectory(DirectoryRepository directory) {
        this.directory = directory;
    }

    /**
     * Leaves out accounts that are suspended, deleted, moved, or on a suspended server. With a viewer, it also leaves out
     * accounts that they've blocked or muted, or blocked the server of, and those that have blocked them, as Mastodon
     * does. The viewer's own account is listed, as on Mastodon.
     *
     * @param viewer    a local account, or empty for someone not signed in
     * @param localOnly whether to list only this server's accounts
     * @return their IDs, in order
     */
    public List<UUID> list(Optional<UUID> viewer, Order order, boolean localOnly, int offset, int limit) {
        return directory.find(viewer, order, localOnly, offset, limit);
    }
}
