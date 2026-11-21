package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class ReportRemoval implements ActorDataRemoval {

    private final Reports reports;

    ReportRemoval(Reports reports) {
        this.reports = reports;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        reports.removeForActor(actorId);
    }

    @Override
    public void removeForDomain(String domain) {
        reports.removeForDomain(domain);
    }
}
