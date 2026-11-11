package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.UUID;

/**
 * Something about a local status that its {@code Note} shows has changed without an edit, such as its quote being
 * approved, or who can quote it.
 *
 * @param updatedAt when, which orders it among the status's edits
 */
public record StatusNoteChanged(UUID statusId, UUID authorId, Instant updatedAt) {
}
