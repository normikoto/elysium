package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores boosts from other servers. A boost of a status that isn't stored here waits for its status to be fetched from its
 * author's server, signed as the instance actor, in a job of its own. Whoever sent the {@code Announce} chose that URL, so
 * these fetches take turns in {@link TargetFetchTurns} with the others made on another server's word. Nothing in the
 * {@code Announce} is trusted about the status, so what's stored is what its server serves.
 */
@Component
class Boosts {

    static final TaskDescriptor<BoostedNoteJob> TASK = TaskDescriptor.of("boosted-note-fetch", BoostedNoteJob.class);

    private static final Logger log = LoggerFactory.getLogger(Boosts.class);

    private final BoostService boosts;
    private final RemoteActors actors;
    private final NoteStorage storage;
    private final StoredActivities stored;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final DomainBlocks blocks;
    private final SchedulerClient scheduler;
    private final JsonMapper json;

    /**
     * @param scheduler lazy, because the scheduler needs the inbound task, which needs {@link AnnounceHandler}, which needs
     *                  this
     */
    Boosts(
            BoostService boosts,
            RemoteActors actors,
            NoteStorage storage,
            StoredActivities stored,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler,
            JsonMapper json) {
        this.boosts = boosts;
        this.actors = actors;
        this.storage = storage;
        this.stored = stored;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.scheduler = scheduler;
        this.json = json;
    }

    /**
     * Only the {@code object}'s ID is read, since an embedded copy is the booster's word for someone else's status.
     *
     * @return the status the {@code Announce} boosts, if it names one by an ID that could be stored
     */
    static Optional<URI> boosted(JsonNode document) {
        return ActivityObject.of(document).map(ActivityObject::id)
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(uri -> "https".equals(uri.getScheme()) && uri.getHost() != null);
    }

    /**
     * Stores the boost of a stored status. Blocks while fetching the booster, so this mustn't be called inside a database
     * transaction.
     */
    void store(InboundActivity activity, JsonNode document, UUID statusId) {
        var booster = actors.fetchOrRefresh(URI.create(activity.actorUri()));
        // Read from the Announce alone, because its object's addressing is the boosted status's.
        var visibility = storage.visibility(Addressing.ofActivity(document), booster);
        if (visibility == Visibility.DIRECT) return;
        var published = Optional.ofNullable(RemoteNote.timestamp(document.path("published")))
                .orElse(activity.firstReceivedAt());
        boosts.receive(booster.id(), statusId, URI.create(activity.id()), visibility, published,
                activity.firstReceivedAt());
    }

    /**
     * Queues a fetch of what an {@code Announce} boosts, unless one is already queued for it.
     */
    void queue(InboundActivity activity) {
        Jobs.schedule(scheduler, TASK,
                new BoostedNoteJob(activity.id(), Instant.now().truncatedTo(ChronoUnit.MICROS), null),
                "boosted-note-fetch", activity.id());
    }

    /**
     * Fetches the boosted status once its host has a turn, stores it, and then the boost.
     *
     * @return what to do with the job, which is to wait for its turn if it hasn't come yet
     * @throws IllegalStateException if the status's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<BoostedNoteJob> run(BoostedNoteJob job) {
        var activity = stored.find(job.announceId()).filter(found -> found.type().equals("Announce"));
        if (activity.isEmpty()) {
            return Jobs.activityGone(job);
        }
        var document = json.readTree(activity.get().body());
        var noteUri = boosted(document);
        var actorUri = URI.create(activity.get().actorUri());
        if (noteUri.isEmpty() || boosts.wasWithdrawn(URI.create(job.announceId()), actorUri)
                || blocks.isBlocked(noteUri.get().getHost()) || blocks.isBlocked(actorUri.getHost())) {
            return Jobs.done();
        }
        try {
            // Throws if the booster has been suspended or deleted while this waited, before the fetch it isn't worth.
            actors.fetchOrRefresh(actorUri);
            // It can have arrived another way while this waited.
            var target = boosts.storedTarget(noteUri.get());
            if (target.isPresent()) {
                store(activity.get(), document, target.get().id());
                return Jobs.done();
            }
            var now = Instant.now();
            var turn = turns.take(noteUri.get(), job.turn(), now);
            if (turn.isEmpty()) {
                log.debug("Ignoring a boost of {}, since too many fetches are waiting for its server", noteUri.get());
                return Jobs.done();
            }
            if (turn.get().isAfter(now)) {
                return Jobs.rescheduleAt(turn.get(), job.withTurn(turn.get()));
            }
            var fetched = AttributedNote.fetch(noteUri.get(), documents);
            if (fetched.isEmpty()) {
                return Jobs.done();
            }
            storage.storeBoosted(fetched.get().note(), fetched.get().author());
            // The note's own ID, which is stored, can be spelled differently from the Announce's, in case or in its host.
            boosts.storedTarget(fetched.get().note().id()).ifPresent(status -> store(activity.get(), document, status.id()));
        } catch (PermanentJobFailure e) {
            // Such as an author or booster that's been suspended here, which isn't worth parking the job for.
            log.debug("Ignoring a boost of {}: {}", noteUri.get(), e.getMessage());
        }
        return Jobs.done();
    }
}
