package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;

import java.net.URI;
import java.util.Optional;

/**
 * Finds the account a command was given. Remote accounts are named by URI, because only a URI identifies them.
 */
final class AccountCommandTarget {

    private AccountCommandTarget() {
    }

    static Optional<Actor> find(ActorService actors, String account) {
        if (account.startsWith("https://") || account.startsWith("http://")) {
            try {
                return actors.findByUri(URI.create(account)).filter(found -> !found.isDeleted());
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return actors.findAnyLocalByUsername(account).filter(found -> !found.isDeleted());
    }
}
