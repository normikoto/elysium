package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
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

    DeletionChecks(
            SchedulerClient scheduler,
            ActivityDocuments documents,
            DomainBlocks blocks,
            Deletions deletions,
            StatusService statuses) {
        this.scheduler = scheduler;
        this.documents = documents;
        this.blocks = blocks;
        this.deletions = deletions;
        this.statuses = statuses;
    }

    /**
     * Queues a check, unless one for the same object is already queued.
     *
     * @param object an object on the actor's server
     */
    void queue(URI object, URI actor) {
        var queuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Jobs.schedule(scheduler, TASK, new DeletionCheckJob(object.toString(), actor.toString(), queuedAt),
                "deletion-check", object.toString(), actor.toString());
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
            if (!object.equals(actor) && !statuses.isPublicRemote(object, actor)) {
                log.debug("Ignoring a Delete of {}, which its server might only be hiding", object);
                return;
            }
        }
        deletions.apply(object, actor);
    }
}
