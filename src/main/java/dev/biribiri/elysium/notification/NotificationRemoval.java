package dev.biribiri.elysium.notification;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class NotificationRemoval implements ActorDataRemoval {

    private final NotificationService notifications;

    NotificationRemoval(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        notifications.removeForActor(actorId);
    }

    @Override
    public void removeForDomain(String domain) {
        notifications.removeForDomain(domain);
    }
}
