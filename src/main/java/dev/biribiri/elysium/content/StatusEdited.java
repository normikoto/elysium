package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An author edited one of their statuses. It can be edited again, or deleted, by the time a listener runs, so listeners have
 * to look it up.
 *
 * @param editedAt      when, because a status can be edited more than once, and each edit needs its own activity
 * @param addedMentions the accounts that this edit mentioned for the first time
 */
public record StatusEdited(UUID statusId, UUID authorId, Instant editedAt, List<UUID> addedMentions) {
}
