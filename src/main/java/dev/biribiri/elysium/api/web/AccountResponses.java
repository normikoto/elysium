package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds account responses, loading what they count for all of the accounts together rather than one query each.
 */
@Component
class AccountResponses {

    private final ActorService actors;
    private final StatusService statuses;
    private final FollowService follows;
    private final InstanceProperties instance;

    AccountResponses(ActorService actors, StatusService statuses, FollowService follows, InstanceProperties instance) {
        this.actors = actors;
        this.statuses = statuses;
        this.follows = follows;
        this.instance = instance;
    }

    AccountResponse one(Actor actor) {
        return build(List.of(actor)).getFirst();
    }

    /**
     * @return a response for each account, in the same order
     */
    List<AccountResponse> build(List<Actor> found) {
        var ids = found.stream().map(Actor::id).toList();
        var stats = statuses.statsFor(ids);
        var counts = follows.countsFor(ids);
        var hiding = actors.hidingCollections(ids);
        return found.stream().map(actor -> {
            var local = actors.isLocal(actor);
            return AccountResponse.from(
                    actor,
                    local ? actor.username() : actor.username() + "@" + actor.domain(),
                    stats.get(actor.id()),
                    counts.get(actor.id()),
                    local ? hiding.contains(actor.id()) : null,
                    instance);
        }).toList();
    }
}
