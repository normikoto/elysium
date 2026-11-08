package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/** A current like. Its activity identity survives inbound activity history cleanup. */
public record StatusLike(UUID id, UUID actorId, UUID statusId, String activityUri, Instant activityAt) {
}
