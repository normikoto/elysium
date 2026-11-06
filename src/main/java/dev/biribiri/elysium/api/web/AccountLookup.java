package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds accounts by {@code username@domain} or by URL, for lookups and searches. Only a signed-in user can make this server
 * ask another server, which it does for an account it doesn't know yet, or hasn't refreshed for a while.
 */
@Component
class AccountLookup {

    private final ActorService actors;
    private final RemoteActorResolver resolver;
    private final InstanceProperties instance;

    AccountLookup(ActorService actors, RemoteActorResolver resolver, InstanceProperties instance) {
        this.actors = actors;
        this.resolver = resolver;
        this.instance = instance;
    }

    /**
     * @param acct    a username, or {@code username@domain}, with or without a leading {@code @}
     * @param viewer  the signed-in user, if there is one
     * @param resolve whether the other server can be asked
     * @return the account, if it exists and is active
     * @throws RemoteActorResolver.ResolveLimitException if the viewer has asked other servers too often
     */
    Optional<Actor> byAcct(String acct, Optional<UUID> viewer, boolean resolve) {
        var trimmed = acct.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        var at = trimmed.indexOf('@');
        if (at < 0) {
            return actors.findLocalByUsername(trimmed);
        }
        var username = trimmed.substring(0, at);
        var domain = trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
        if (username.isEmpty() || domain.isEmpty() || domain.contains("@")) {
            return Optional.empty();
        }
        if (domain.equals(instance.domain())) {
            return actors.findLocalByUsername(username);
        }
        if (resolve && viewer.isPresent()) {
            return resolver.resolveAcct(viewer.get(), username, domain);
        }
        return actors.findRemoteByAcct(username, domain).filter(Actor::isActive);
    }

    /**
     * @param url the actor's ID, or a URL its server serves it at, such as its profile page
     * @return the account, if it exists and is active
     * @throws RemoteActorResolver.ResolveLimitException if the viewer has asked other servers too often
     */
    Optional<Actor> byUrl(String url, Optional<UUID> viewer, boolean resolve) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        var stored = actors.findByUri(uri).filter(Actor::isActive);
        if (stored.isPresent() && actors.isLocal(stored.get())) {
            return stored;
        }
        if (resolve && viewer.isPresent()) {
            return resolver.resolveUrl(viewer.get(), uri);
        }
        return stored;
    }
}
