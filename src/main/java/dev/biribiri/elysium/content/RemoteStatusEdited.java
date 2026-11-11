package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Another server edited a status that's stored here.
 */
public record RemoteStatusEdited(UUID statusId, UUID authorId, Instant editedAt) {
}
