package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import org.springframework.stereotype.Component;

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
     * @param fresh   whether an account on another server's document has to be fetched again, rather than a recent copy
     *                used, because what it says right now matters
     * @return the account, or empty if there's none to be found
     * @throws RemoteActorResolver.ResolveLimitException if the requester has looked up too many accounts recently
     */
    Optional<Actor> find(UUID requester, String address, boolean fresh) {
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
        if (domain.equals(instance.domain())) {
            return actors.findLocalByUsername(username);
        }
        return fresh ? resolver.resolveAcctFresh(requester, username, domain) : resolver.resolveAcct(requester, username, domain);
    }
}
