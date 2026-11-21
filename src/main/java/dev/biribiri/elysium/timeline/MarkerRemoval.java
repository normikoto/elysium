package dev.biribiri.elysium.timeline;

import dev.biribiri.elysium.actor.ActorDataRemoval;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class MarkerRemoval implements ActorDataRemoval {

    private final Markers markers;

    MarkerRemoval(Markers markers) {
        this.markers = markers;
    }

    @Override
    public void removeForDeleted(UUID actorId) {
        markers.removeForActor(actorId);
    }

    /**
     * Only local accounts have markers.
     */
    @Override
    public void removeForDomain(String domain) {
    }
}
