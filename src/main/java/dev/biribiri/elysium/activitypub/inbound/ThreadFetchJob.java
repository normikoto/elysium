package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.jobs.QueuedJob;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Fills in a status's thread, through {@link ThreadFetches}. One job walks up its parents, one at a time, and the others
 * read the replies to a status in the thread, or fetch one of those replies, or read the collection of the thread's posts
 * that the status's {@code context} names, or fetch one of those posts.
 *
 * @param statusId  the status whose thread is being filled in
 * @param queuedAt  when the fetch that this job is part of started, which every job of that fetch shares, so that it tells
 *                  them apart from a later fetch's
 * @param step      what the job fetches
 * @param parentId  for {@link Step#REPLIES}, the status whose replies are read, and for {@link Step#REPLY}, the status that
 *                  the reply has to answer, or null for the other steps
 * @param target    for {@link Step#PARENTS}, the last parent fetched, or null before the first. For {@link Step#REPLIES},
 *                  the collection or page to read next, or null to start from the status's own document. For
 *                  {@link Step#REPLY} and {@link Step#CONTEXT_POST}, the reply or post. For {@link Step#CONTEXT}, the
 *                  collection or page to read next.
 * @param depth     for {@link Step#REPLIES} and {@link Step#REPLY}, how many replies down from {@code statusId} the replies
 *                  being read or fetched are, starting from 1. For {@link Step#CONTEXT}, how many posts it has queued.
 * @param pages     how many pages of the collection have been read, counting a collection fetched by its ID. For
 *                  {@link Step#CONTEXT_POST}, how many times it has waited for its parent to be stored.
 * @param turn      the turn {@link TargetFetchTurns} gave it for the next fetch, or null if it hasn't waited for one
 * @param contextId the collection of the thread's posts, for {@link Step#CONTEXT} and {@link Step#CONTEXT_POST}. On a
 *                  {@link Step#REPLIES} job, it means the collection was tried and couldn't be read, so the replies are
 *                  read instead. Null otherwise, and for jobs queued before there was a context.
 */
record ThreadFetchJob(UUID statusId, Instant queuedAt, Step step, UUID parentId, String target, int depth, int pages,
                      Instant turn, String contextId) implements QueuedJob {

    enum Step {
        PARENTS,
        REPLIES,
        REPLY,
        CONTEXT,
        CONTEXT_POST
    }

    static ThreadFetchJob parents(UUID statusId, Instant queuedAt) {
        return new ThreadFetchJob(statusId, queuedAt, Step.PARENTS, null, null, 0, 0, null, null);
    }

    static ThreadFetchJob replies(UUID statusId, Instant queuedAt) {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLIES, statusId, null, 1, 0, null, null);
    }

    ThreadFetchJob withTurn(Instant turn) {
        return new ThreadFetchJob(statusId, queuedAt, step, parentId, target, depth, pages, turn, contextId);
    }

    /**
     * The same job, once it's fetched this parent, for the next one.
     */
    ThreadFetchJob afterParent(URI parent) {
        return new ThreadFetchJob(statusId, queuedAt, step, parentId, parent.toString(), depth, pages, null, contextId);
    }

    /**
     * Goes on to read the replies to a status from a collection or page that needs a fetch of its own.
     *
     * @param pages how many pages have been read before it
     */
    ThreadFetchJob repliesAt(UUID parentId, int depth, URI target, int pages) {
        return new ThreadFetchJob(
                statusId, queuedAt, Step.REPLIES, parentId, target.toString(), depth, pages, null, contextId);
    }

    /**
     * A job of its own that fetches one reply to this status, as part of the same fetch.
     */
    ThreadFetchJob reply(UUID parentId, int depth, URI reply) {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLY, parentId, reply.toString(), depth, 0, null, null);
    }

    /**
     * Goes on from the status's own document to the collection of its thread's posts.
     */
    ThreadFetchJob context(URI collection) {
        return new ThreadFetchJob(
                statusId, queuedAt, Step.CONTEXT, null, collection.toString(), 0, 0, null, collection.toString());
    }

    /**
     * Goes on to read a page of the collection of the thread's posts.
     *
     * @param pages  how many pages have been read before it
     * @param queued how many posts have been queued before it
     */
    ThreadFetchJob contextAt(URI page, int pages, int queued) {
        return new ThreadFetchJob(statusId, queuedAt, Step.CONTEXT, null, page.toString(), queued, pages, null, contextId);
    }

    /**
     * A job of its own that fetches one post that the collection of the thread's posts lists.
     */
    ThreadFetchJob contextPost(URI post) {
        return new ThreadFetchJob(statusId, queuedAt, Step.CONTEXT_POST, null, post.toString(), 0, 0, null, contextId);
    }

    /**
     * The same post, to fetch again once its parent has had time to be stored.
     */
    ThreadFetchJob waitedForParent() {
        return new ThreadFetchJob(statusId, queuedAt, step, null, target, 0, pages + 1, null, contextId);
    }

    /**
     * Reads the status's replies after all, since the collection its {@code context} names couldn't be read.
     */
    ThreadFetchJob repliesWithoutContext() {
        return new ThreadFetchJob(statusId, queuedAt, Step.REPLIES, statusId, null, 1, 0, null, contextId);
    }
}
