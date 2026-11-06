package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
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
    private final JsonMapper json;

    ForwardedActivities(
            SchedulerClient scheduler,
            StoredActivities stored,
            InboundActivities activities,
            ActivityDocuments documents,
            DomainBlocks blocks,
            ActorService actors,
            JsonMapper json) {
        this.scheduler = scheduler;
        this.stored = stored;
        this.activities = activities;
        this.documents = documents;
        this.blocks = blocks;
        this.actors = actors;
        this.json = json;
    }

    /**
     * Queues a fetch of the activity, unless one is already queued.
     *
     * @param id an ID that {@link Inbox} has checked
     */
    void queue(String id) {
        Jobs.schedule(scheduler, TASK, new ForwardedJob(id, Instant.now()), "forwarded", id);
    }

    /**
     * Fetches the activity, and stores it if the origin vouches for it. This has to be called with no transaction open,
     * because it waits on another server.
     *
     * @throws IllegalStateException if the origin couldn't be reached, which is worth trying again
     */
    void fetch(String id) {
        // It can have been delivered by its own actor since this was queued.
        if (stored.exists(id)) {
            return;
        }
        var uri = URI.create(id);
        // A block can have been added since this was queued.
        if (blocks.isBlocked(uri.getHost())) {
            return;
        }
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
            return;
        }
        if (body.length > Inbox.MAX_BODY_BYTES) {
            log.debug("Dropping forwarded activity {}, whose origin's copy is too large", id);
            return;
        }
        var envelope = ActivityEnvelope.parse(body, json);
        if (envelope.isEmpty() || !id.equals(envelope.get().id()) || envelope.get().actor() == null) {
            log.debug("Dropping forwarded activity {}, which its origin doesn't serve as one activity with one actor", id);
            return;
        }
        var actor = ActivityObject.parseUri(envelope.get().actor());
        // The origin decides who the actor is, but it can only speak for its own.
        if (actor.isEmpty() || !Inbox.isOnActorsServer(uri, actor.get())) {
            log.debug("Dropping forwarded activity {}, whose actor is on another server", id);
            return;
        }
        if (actors.isSuspended(actor.get())) {
            return;
        }
        InboundActivity activity;
        try {
            activity = new InboundActivity(id, envelope.get().actor(), envelope.get().type(), envelope.get().body());
        } catch (IllegalArgumentException e) {
            return;
        }
        activities.accept(activity);
    }
}
