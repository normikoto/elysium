package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Handles activities that were signed by someone other than their actor, which is what forwarding looks like: Mastodon
 * forwards a reply to the followers of the account being replied to, for example. The signer can't speak for the activity,
 * so it's fetched from its origin by its {@code id}, and the origin's copy is what's stored. Linked Data Signatures aren't
 * checked, because they need a JSON-LD processor.
 *
 * <p>Nothing is stored under the ID until the origin has answered. Otherwise a server could forward a forged activity under
 * a real one's ID, and the real one would then be dropped as a repeat.
 */
@Component
class ForwardedActivities {

    static final TaskDescriptor<ForwardedJob> TASK = TaskDescriptor.of("forwarded-activity", ForwardedJob.class);

    private static final Logger log = LoggerFactory.getLogger(ForwardedActivities.class);

    private final SchedulerClient scheduler;
    private final StoredActivities stored;
    private final InboundActivities activities;
    private final ActivityDocuments documents;
    private final DomainBlocks blocks;
    private final ActorService actors;
    private final DeletionChecks deletions;
    private final TargetFetchTurns turns;
    private final JsonMapper json;

    ForwardedActivities(
            SchedulerClient scheduler,
            StoredActivities stored,
            InboundActivities activities,
            ActivityDocuments documents,
            DomainBlocks blocks,
            ActorService actors,
            DeletionChecks deletions,
            TargetFetchTurns turns,
            JsonMapper json) {
        this.scheduler = scheduler;
        this.stored = stored;
        this.activities = activities;
        this.documents = documents;
        this.blocks = blocks;
        this.actors = actors;
        this.deletions = deletions;
        this.turns = turns;
        this.json = json;
    }

    /**
     * Queues a fetch of the activity, unless one is already queued.
     *
     * @param id            an ID that {@link Inbox} has checked
     * @param deletedObject for a {@code Delete}, its object, checked to be on the actor's server, and otherwise empty
     */
    void queue(String id, URI actor, Optional<URI> deletedObject) {
        // PostgreSQL stores microseconds, so this matches what a later read of the activity returns.
        var arrivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var job = new ForwardedJob(
                id,
                arrivedAt,
                deletedObject.map(URI::toString).orElse(null),
                deletedObject.isPresent() ? actor.toString() : null);
        Jobs.schedule(scheduler, TASK, job, "forwarded", id);
    }

    /**
     * Fetches the activity, and stores it if the origin vouches for it, once the origin's host has a turn in
     * {@link TargetFetchTurns}. A {@code Delete} that the origin doesn't vouch for is checked with its object's server
     * instead, in a job of its own, which takes its own turn. This has to be called with no transaction open, because it
     * waits on other servers.
     *
     * @return what to do with the job, which is to wait for its turn if it hasn't come yet
     * @throws IllegalStateException if a server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ForwardedJob> fetch(ForwardedJob job) {
        var id = job.activityId();
        // It can have been delivered by its own actor since this was queued.
        if (stored.exists(id)) {
            return Jobs.done();
        }
        var uri = URI.create(id);
        // A block can have been added since this was queued.
        if (blocks.isBlocked(uri.getHost())) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(uri, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Dropping forwarded activity {}, since too many fetches are waiting for its origin", id);
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.rescheduleAt(turn.get(), job.withTurn(turn.get()));
        }
        if (!fetch(uri, job.queuedAt()) && job.deletedObject() != null) {
            deletions.queue(URI.create(job.deletedObject()), URI.create(job.actorId()), job.queuedAt());
        }
        return Jobs.done();
    }

    /**
     * @param arrivedAt when the forwarded copy arrived, which is stored as when the activity did. Handlers order activities
     *                  by arrival, so the time of the fetch would put this after anything that arrived while it waited, such
     *                  as an {@code Undo} of a forwarded {@code Follow}.
     * @return whether the activity is stored, by this
     */
    private boolean fetch(URI uri, Instant arrivedAt) {
        var id = uri.toString();
        byte[] body;
        try {
            body = json.writeValueAsString(documents.fetch(uri)).getBytes(StandardCharsets.UTF_8);
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            // This isn't something for an admin to fix. Many servers don't serve their activities, and a forged one has
            // nothing to find.
            log.debug("Dropping forwarded activity {}: {}", id, e.getMessage());
            return false;
        }
        if (body.length > Inbox.MAX_BODY_BYTES) {
            log.debug("Dropping forwarded activity {}, whose origin's copy is too large", id);
            return false;
        }
        var envelope = ActivityEnvelope.parse(body, json);
        if (envelope.isEmpty() || !id.equals(envelope.get().id()) || envelope.get().actor() == null) {
            log.debug("Dropping forwarded activity {}, which its origin doesn't serve as one activity with one actor", id);
            return false;
        }
        var actor = ActivityObject.parseUri(envelope.get().actor());
        // The origin decides who the actor is, but it can only speak for its own.
        if (actor.isEmpty() || !Inbox.isOnActorsServer(uri, actor.get())) {
            log.debug("Dropping forwarded activity {}, whose actor is on another server", id);
            return false;
        }
        if (actors.isInactive(actor.get())) {
            return false;
        }
        InboundActivity activity;
        try {
            activity = new InboundActivity(id, envelope.get().actor(), envelope.get().type(), envelope.get().body(), arrivedAt);
        } catch (IllegalArgumentException e) {
            return false;
        }
        activities.accept(activity);
        return true;
    }
}
