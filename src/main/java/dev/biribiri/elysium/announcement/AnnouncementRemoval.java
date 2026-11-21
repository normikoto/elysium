package dev.biribiri.elysium.announcement;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class AnnouncementRemoval implements ActorDataRemoval {

    private final Announcements announcements;

    AnnouncementRemoval(Announcements announcements) {
        this.announcements = announcements;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        announcements.removeForActor(actorId);
    }

    /**
     * Only local accounts dismiss or react to announcements, so there's nothing about another server's actors.
     */
    @Override
    public void removeForDomain(String domain) {
    }
}
