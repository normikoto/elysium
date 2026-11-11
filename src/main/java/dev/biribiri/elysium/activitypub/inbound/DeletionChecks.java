package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Carries out a {@code Delete} that can't be shown to come from its actor, once the object's own server says the object is
 * gone. That's how a {@code Delete} is checked when it's forwarded and its origin doesn't serve it, as Mastodon doesn't, and
 * when its signature can't be checked because its actor's key went with the actor. Nothing in the {@code Delete} is trusted,
 * since the object's server is what decides.
 */
@Component
class DeletionChecks {

    static final TaskDescriptor<DeletionCheckJob> TASK = TaskDescriptor.of("deletion-check", DeletionCheckJob.class);

    private static final Logger log = LoggerFactory.getLogger(DeletionChecks.class);

    private final SchedulerClient scheduler;
    private final ActivityDocuments documents;
    private final DomainBlocks blocks;
    private final Deletions deletions;
    private final StatusService statuses;
    private final TargetFetchTurns turns;
    private final QuoteService quotes;

    DeletionChecks(
            SchedulerClient scheduler,
            ActivityDocuments documents,
            DomainBlocks blocks,
            Deletions deletions,
            StatusService statuses,
            TargetFetchTurns turns,
            QuoteService quotes) {
        this.quotes = quotes;
        this.scheduler = scheduler;
        this.documents = documents;
        this.blocks = blocks;
        this.deletions = deletions;
        this.statuses = statuses;
        this.turns = turns;
    }

    /**
     * Queues a check, unless one for the same object is already queued.
     *
     * @param object an object on the actor's server
     */
    void queue(URI object, URI actor) {
        queue(object, actor, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * @param queuedAt when the {@code Delete} arrived, for one that has already waited in another job, so that retries are
     *                 timed from then
     */
    void queue(URI object, URI actor, Instant queuedAt) {
        Jobs.schedule(scheduler, TASK, new DeletionCheckJob(object.toString(), actor.toString(), queuedAt, null),
                "deletion-check", object.toString(), actor.toString());
    }

    /**
     * Runs {@link #confirm} once the object's host has a turn in {@link TargetFetchTurns}, since the object comes from
     * whoever sent the {@code Delete}.
     *
     * @return what to do with the job, which is to wait for its turn if it hasn't come yet
     */
    CompletionHandler<DeletionCheckJob> run(DeletionCheckJob job) {
        var object = URI.create(job.objectId());
        // confirm checks again, but a blocked host shouldn't use up turns or wait for one.
        if (blocks.isBlocked(object.getHost())) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(object, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Ignoring a Delete of {}, since too many fetches are waiting for its server", object);
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.rescheduleAt(turn.get(), job.withTurn(turn.get()));
        }
        confirm(object, URI.create(job.actorId()));
        return Jobs.done();
    }

    /**
     * Fetches the object, and deletes it here if its server says it's gone, with a {@code Tombstone}, or with a 404 or a 410
     * for the actor or for a status that anyone could see. This has to be called with no transaction open, because it waits
     * on another server.
     *
     * @param object an object on the actor's server
     * @throws IllegalStateException if the object's server couldn't be reached, which is worth trying again
     */
    void confirm(URI object, URI actor) {
        // A block can have been added since this was queued.
        if (blocks.isBlocked(object.getHost())) {
            return;
        }
        try {
            var document = documents.fetch(object);
            var isTombstone = ActivityJson.types(document.path("type")).contains("Tombstone")
                    && object.toString().equals(ActivityJson.text(document.path("id")));
            if (!isTombstone) {
                log.debug("Ignoring a Delete of {}, which its server still serves", object);
                return;
            }
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!e.isGone()) {
                log.debug("Ignoring a Delete of {}, which couldn't be checked: {}", object, e.getMessage());
                return;
            }
            // Servers such as Mastodon answer the instance actor with a 404 for a followers-only status too, so that only
            // shows a status is gone if anyone could have fetched it. Otherwise anyone could forward a Delete of one.
            // A QuoteAuthorization is named in its quote, so saying it's gone tells nobody anything.
            if (!object.equals(actor) && !statuses.isPublicRemote(object, actor) && !quotes.isKnownApproval(object)) {
                log.debug("Ignoring a Delete of {}, which its server might only be hiding", object);
                return;
            }
        }
        deletions.apply(object, actor);
    }
}
