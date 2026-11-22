package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.RemoteUrlResolver;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds accounts for both searches, so that {@code /api/v1/accounts/search} and {@code /api/v2/search} agree on what a query
 * finds. An address or URL names one account, and any other query matches the start of usernames.
 *
 * <p>Only the first {@value AccountFilter#MAX_LIMIT} matches can be paged through, since PostgreSQL finds every one before it
 * skips any.
 */
@Component
class AccountSearch {

    private static final Pattern ACCT = Pattern.compile("@?[^@\\s]+@[^@\\s]+");
    private static final Pattern USERNAME_PREFIX = Pattern.compile("@?[A-Za-z0-9_]{1,30}");

    private final ActorService actors;
    private final AccountLookup lookup;
    private final FollowService follows;

    AccountSearch(ActorService actors, AccountLookup lookup, FollowService follows) {
        this.actors = actors;
        this.lookup = lookup;
        this.follows = follows;
    }

    static boolean isAddress(String query) {
        return ACCT.matcher(query).matches();
    }

    static boolean isUrl(String query) {
        return query.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    /**
     * @param query     a stripped, non-blank query
     * @param following whether to only find accounts the viewer follows. Ignored without a viewer, who follows no one.
     * @throws dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException if the viewer has asked other servers
     *                                                                             too often
     */
    List<Actor> find(String query, Optional<UUID> viewer, boolean resolve, boolean following, int pageSize, int offset) {
        var followedBy = following ? viewer : Optional.<UUID>empty();
        var skipped = Math.min(offset, AccountFilter.MAX_LIMIT);
        if (isUrl(query)) {
            return skipped > 0 ? List.of() : fromUrl(lookup.byUrl(query, viewer, resolve), followedBy);
        }
        if (isAddress(query)) {
            return skipped > 0 ? List.of() : followedOnly(lookup.byAcct(query, viewer, resolve), followedBy).stream().toList();
        }
        if (USERNAME_PREFIX.matcher(query).matches()) {
            var prefix = query.startsWith("@") ? query.substring(1) : query;
            return followedBy.isPresent()
                    ? followedByUsernamePrefix(followedBy.get(), prefix, pageSize, skipped)
                    : byUsernamePrefix(prefix, pageSize, skipped);
        }
        return List.of();
    }

    /**
     * The account a URL lookup found, for a search that looked it up once for statuses too.
     *
     * @param followedBy the viewer, when only accounts they follow are found
     */
    List<Actor> fromUrl(Optional<RemoteUrlResolver.Found> found, Optional<UUID> followedBy) {
        var account = found.flatMap(at -> at instanceof RemoteUrlResolver.Found.Account(var actor)
                ? Optional.of(actor)
                : Optional.empty());
        return followedOnly(account, followedBy).stream().toList();
    }

    private Optional<Actor> followedOnly(Optional<Actor> actor, Optional<UUID> followedBy) {
        return actor.filter(found -> followedBy.isEmpty() || follows.isFollowing(followedBy.get(), found.id()));
    }

    private List<Actor> byUsernamePrefix(String prefix, int pageSize, int skipped) {
        // The filter loads at most its maximum, which leaves nothing after a large offset.
        var filter = new AccountFilter(null, false, prefix, null, pageSize + skipped, null, null, null);
        return actors.list(filter).stream().filter(Actor::isActive).skip(skipped).limit(pageSize).toList();
    }

    private List<Actor> followedByUsernamePrefix(UUID viewer, String prefix, int pageSize, int skipped) {
        var limit = Math.min(pageSize, AccountFilter.MAX_LIMIT - skipped);
        if (limit <= 0) {
            return List.of();
        }
        var ids = follows.followedIdsByUsernamePrefix(viewer, prefix, limit, skipped);
        var byId = actors.findByIds(ids).stream().collect(Collectors.toMap(Actor::id, Function.identity()));
        return ids.stream().map(byId::get).filter(actor -> actor != null && actor.isActive()).toList();
    }
}
