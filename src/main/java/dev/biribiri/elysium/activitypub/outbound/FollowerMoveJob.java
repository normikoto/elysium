package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Moves local followers, blocks and mutes of an account that moved to the account it moved to.
 */
record FollowerMoveJob(UUID moveId, Instant queuedAt) implements QueuedJob {
}
