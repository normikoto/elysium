package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds account responses, loading what they count for all of the accounts together rather than one query each.
 */
@Component
class AccountResponses {

    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;
    private final InstanceProperties instance;
    private final ProfileService profiles;

    AccountResponses(
            ActorService actors,
            StatusService statuses,
            FollowService follows,
            InstanceProperties instance,
            ProfileService profiles) {
        this.actors = actors;
        this.profiles = profiles;
        this.statuses = statuses;
        this.follows = follows;
        this.instance = instance;
    }

    AccountResponse one(Actor actor) {
        return byId(List.of(actor)).get(actor.id());
    }

    /**
     * The account as its owner sees it, which is only ever a local one.
     */
    CredentialAccountResponse credential(Actor actor) {
        var profile = profiles.find(actor.id());
        var account = byId(List.of(actor), Map.of(actor.id(), profile)).get(actor.id());
        return CredentialAccountResponse.from(account, profile, follows.countRequests(actor.id()));
    }

    /**
     * @return a response for each account, in the same order
     */
    List<AccountResponse> build(List<Actor> found) {
        var responses = byId(found);
        return found.stream().map(actor -> responses.get(actor.id())).toList();
    }

    /**
     * @return a response for each account, by its ID
     */
    Map<UUID, AccountResponse> byId(Collection<Actor> found) {
        return byId(found, profiles.findAll(found.stream().filter(actors::isLocal).map(Actor::id).toList()));
    }

    private Map<UUID, AccountResponse> byId(Collection<Actor> found, Map<UUID, Profile> profilesById) {
        var ids = found.stream().map(Actor::id).toList();
        var stats = statuses.statsFor(ids);
        var counts = follows.countsFor(ids);
        var hiding = actors.hidingCollections(ids);
        var responses = new HashMap<UUID, AccountResponse>();
        for (var actor : found) {
            var local = actors.isLocal(actor);
            responses.put(actor.id(), AccountResponse.from(
                    actor,
                    local ? actor.username() : actor.username() + "@" + actor.domain(),
                    local ? profilesById.getOrDefault(actor.id(), Profile.EMPTY) : null,
                    stats.get(actor.id()),
                    counts.get(actor.id()),
                    local ? hiding.contains(actor.id()) : null,
                    instance));
        }
        return responses;
    }
}
