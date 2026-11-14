package dev.biribiri.elysium.content;

import java.util.UUID;

/** A local account pinned a status. Listeners must check that the pin is still current before sending it. */
public record StatusPinned(UUID pinId, UUID actorId, UUID statusId) {
}
