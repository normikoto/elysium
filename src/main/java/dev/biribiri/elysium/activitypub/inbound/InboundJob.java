package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;

/**
 * Processes one activity, which is in {@link InboundActivities}'s table.
 */
record InboundJob(String activityId, Instant queuedAt) implements QueuedJob {
}
