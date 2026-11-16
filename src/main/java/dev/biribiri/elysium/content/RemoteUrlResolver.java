package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.RemoteActorResolver;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds what's at a URL someone has a link to, an account or a status, for modules that can't depend on the federation
 * code. The {@code activitypub} module implements this.
 *
 * <p>One URL can be either, and only its server's answer says which, so both are looked up together, with one request
 * and one count toward the requester's limit. Like {@link RemoteActorResolver}, this blocks while making requests, so it
 * mustn't be called inside a database transaction, and each request counts toward the same limit of
 * {@link RemoteActorResolver#LOOKUPS_PER_MINUTE}.
 */
public interface RemoteUrlResolver {

    /**
     * Finds the account or status at a URL, which can be its ID, or a page whose server serves it at the same URL, such as
     * a profile page or a status's page. What's stored here already is found without asking. A status from another server
     * is only stored if anyone can see it.
     *
     * @param requester the local account asking, whose limit this counts toward
     * @return what's there, or empty if it isn't an account or status, it can't be fetched right now, or its server is
     * blocked. Whether the requester can see a status is up to the caller.
     * @throws RemoteActorResolver.ResolveLimitException if the requester has made too many lookups recently
     */
    Optional<Found> resolveUrl(UUID requester, URI url);

    /**
     * What a URL turned out to be.
     */
    sealed interface Found {

        record Account(Actor actor) implements Found {
        }

        record Post(Status status) implements Found {
        }
    }
}
