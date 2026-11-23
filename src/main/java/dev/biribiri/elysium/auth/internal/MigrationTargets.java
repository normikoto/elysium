package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds the account named on the alias and move pages, from an address such as {@code @alice@example.com}, or a username
 * for an account on this server.
 */
@Component
class MigrationTargets {

    private final ActorService actors;
    private final RemoteActorResolver resolver;
    private final InstanceProperties instance;

    MigrationTargets(ActorService actors, RemoteActorResolver resolver, InstanceProperties instance) {
        this.actors = actors;
        this.resolver = resolver;
        this.instance = instance;
    }

    /**
     * @param address what was typed in
     * @return the account, or empty if there's none to be found
     * @throws RemoteActorResolver.ResolveLimitException if the requester has looked up too many accounts recently
     */
    Optional<Actor> find(UUID requester, String address) {
        return find(requester, address, null);
    }

    /**
     * Like {@link #find(UUID, String)}, but an account on another server's document is fetched again rather than a recent
     * copy used, since whether it lists the requester as an alias may have only just changed.
     *
     * @param mover the requester's URI, which is kept in what's stored of the account's aliases if it lists it
     */
    Optional<Actor> findFresh(UUID requester, String address, URI mover) {
        return find(requester, address, mover);
    }

    private Optional<Actor> find(UUID requester, String address, URI mover) {
        if (address == null) {
            return Optional.empty();
        }
        var trimmed = address.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        var at = trimmed.indexOf('@');
        if (at < 0) {
            return trimmed.isEmpty() ? Optional.empty() : actors.findLocalByUsername(trimmed);
        }
        var username = trimmed.substring(0, at);
        var domain = trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
        if (username.isEmpty() || domain.isEmpty()) {
            return Optional.empty();
        }
        if (instance.isLocalDomain(domain)) {
            return actors.findLocalByUsername(username);
        }
        return mover != null
                ? resolver.resolveAcctFresh(requester, username, domain, mover)
                : resolver.resolveAcct(requester, username, domain);
    }
}
