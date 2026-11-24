package dev.biribiri.elysium.push.internal;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class PushRemoval implements ActorDataRemoval {

    private final PushSubscriptionRepository subscriptions;

    PushRemoval(PushSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    /**
     * Deleting an account already revokes its tokens, which deletes their subscriptions, but this doesn't rely on it.
     */
    @Override
    public void removeForDeleted(UUID actorId) {
        subscriptions.removeForActor(actorId);
    }

    /**
     * Only local accounts subscribe.
     */
    @Override
    public void removeForDomain(String domain) {
    }
}
