package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.internal.DirectoryRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        ACTIVE("active"),
        /**
         * Those that joined this server, or that this server first heard of, most recently first.
         */
        NEW("new");

        private final String value;

        Order(String value) {
            this.value = value;
        }

        /**
         * Mastodon's name for the order, which the web page uses too.
         */
        public String value() {
            return value;
        }

        /**
         * @param value one of the {@link #value}s, or null or empty for {@link #ACTIVE}
         * @return empty for any other value
         */
        public static Optional<Order> parse(String value) {
            if (value == null || value.isEmpty()) {
                return Optional.of(ACTIVE);
            }
            return Arrays.stream(values()).filter(order -> order.value.equals(value)).findFirst();
        }
    }

    private final DirectoryRepository directory;
    private final ActorService actors;
    private final ProfileService profiles;

    AccountDirectory(DirectoryRepository directory, ActorService actors, ProfileService profiles) {
        this.directory = directory;
        this.actors = actors;
        this.profiles = profiles;
    }

    /**
     * Leaves out accounts that are suspended, deleted, moved, or on a suspended server. With a viewer, it also leaves out
     * accounts that they've blocked or muted, or blocked the server of, and those that have blocked them, as Mastodon
     * does. The viewer's own account is listed, as on Mastodon.
     *
     * @param viewer    a local account, or empty for someone not signed in
     * @param localOnly whether to list only this server's accounts
     * @return the accounts in order, or empty if the operator has turned the directory off
     */
    public Optional<List<Actor>> list(Optional<UUID> viewer, Order order, boolean localOnly, int offset, int limit) {
        if (!profiles.directoryShown()) {
            return Optional.empty();
        }
        var ids = directory.find(viewer, order, localOnly, offset, limit);
        var found = actors.findByIds(ids).stream().collect(Collectors.toMap(Actor::id, Function.identity()));
        return Optional.of(ids.stream().map(found::get).filter(Objects::nonNull).toList());
    }
}
