package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Fills in a status's thread, through {@link ThreadFetches}. One job walks up its parents, one at a time, and the others
 * read the replies to a status in the thread, or fetch one of those replies.
 *
 * @param statusId the status whose thread is being filled in
 * @param queuedAt when the fetch that this job is part of started, which every job of that fetch shares, so that it tells
 *                 them apart from a later fetch's
 * @param step     what the job fetches
 * @param parentId for {@link Step#REPLIES}, the status whose replies are read, and for {@link Step#REPLY}, the status that
 *                 the reply has to answer, or null for {@link Step#PARENTS}
 * @param target   for {@link Step#PARENTS}, the last parent fetched, or null before the first. For {@link Step#REPLIES},
 *                 the collection or page to read next, or null to start from the status's own document. For
 *                 {@link Step#REPLY}, the reply.
 * @param depth    how many replies down from {@code statusId} the replies being read or fetched are, starting from 1
 * @param pages    how many pages of the replies collection have been read, counting a collection fetched by its ID
 * @param turn     the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 */
record ThreadFetchJob(UUID statusId, Instant queuedAt, Step step, UUID parentId, String target, int depth, int pages,
                      Instant turn) implements QueuedJob {

    enum Step {
        PARENTS,
        REPLIES,
        REPLY
    }

    static ThreadFetchJob parents(UUID statusId, Instant queuedAt) {
        return new ThreadFetchJob(statusId, queuedAt, Step.PARENTS, null, null, 0, 0, null);
    }

    static ThreadFetchJob replies(UUID statusId, Instant queuedAt) {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLIES, statusId, null, 1, 0, null);
    }

    ThreadFetchJob withTurn(Instant turn) {
        return new ThreadFetchJob(statusId, queuedAt, step, parentId, target, depth, pages, turn);
    }

    /**
     * The same job, once it's fetched this parent, for the next one.
     */
    ThreadFetchJob afterParent(URI parent) {
        return new ThreadFetchJob(statusId, queuedAt, step, parentId, parent.toString(), depth, pages, null);
    }

    /**
     * Goes on to read the replies to a status from a collection or page that needs a fetch of its own.
     *
     * @param pages how many pages have been read before it
     */
    ThreadFetchJob repliesAt(UUID parentId, int depth, URI target, int pages) {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLIES, parentId, target.toString(), depth, pages, null);
    }

    /**
     * A job of its own that fetches one reply to this status, as part of the same fetch.
     */
    ThreadFetchJob reply(UUID parentId, int depth, URI reply) {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLY, parentId, reply.toString(), depth, 0, null);
    }
}
