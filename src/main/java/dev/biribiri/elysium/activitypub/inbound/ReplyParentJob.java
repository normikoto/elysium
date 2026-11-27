package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.time.Instant;

/**
 * Keeps a reply that was addressed to nobody here once its parent is stored, through {@link ReplyParentChecks}.
 *
 * @param activityId   the {@code Create} or {@code Update} of the reply, which is handled again once the parent is stored
 * @param parentUri    the reply's {@code inReplyTo}
 * @param fetchQueued  whether this has queued a {@link ParentFetchJob}, which it does if the parent hasn't arrived after
 *                     its first wait
 */
record ReplyParentJob(String activityId, String parentUri, Instant queuedAt, boolean fetchQueued) implements QueuedJob {

    ReplyParentJob afterQueuingFetch() {
        return new ReplyParentJob(activityId, parentUri, queuedAt, true);
    }
}
