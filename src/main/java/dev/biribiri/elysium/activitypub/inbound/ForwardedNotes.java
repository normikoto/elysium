package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.Digests;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Stores the note of a forwarded {@code Create} or {@code Update} that its origin doesn't serve, once the note's own server
 * serves it as the actor's. That's the server that speaks for the note, so what it serves is stored as an activity of the
 * same type of its own, and nothing from the forwarded copy is kept but the note's ID. The note's addressing decides who
 * it's for. An {@code Update} is then handled as any other is, so its {@code updated} decides whether it's newer than
 * what's stored, and a note that isn't stored yet is stored as old if it is.
 *
 * <p>That activity isn't stored under the forwarded copy's {@code id}, which nobody has vouched for. Otherwise a server could
 * forward one that gives a real activity's {@code id} to another of its actor's notes, and the real one would be dropped as
 * a repeat. A {@code Create}'s ID comes from the note instead, so forwards of the same note are repeats of each other. An
 * {@code Update}'s comes from what was served, since each edit is a new version of the same note, but a version fetched
 * again is still applied again. The real activity still arrives under its own ID, and finds the note already stored.
 */
@Component
class ForwardedNotes {

    static final TaskDescriptor<ForwardedNoteJob> TASK = TaskDescriptor.of("forwarded-note", ForwardedNoteJob.class);

    /**
     * How long before it was forwarded a note can have been published, for a {@code Create}. Anyone can forward one, so
     * without this a server could have an actor's old notes stored here as new, where they'd go to the top of timelines and
     * notify. A {@code Create} is forwarded when it's delivered, which is within this of its note being posted. An
     * {@code Update} doesn't need it, since one of an old note is stored as old.
     */
    static final Duration NEW_FOR = Jobs.RETRIED_FOR;

    private static final Logger log = LoggerFactory.getLogger(ForwardedNotes.class);

    private final SchedulerClient scheduler;
    private final StoredActivities stored;
    private final InboundActivities activities;
    private final ActivityDocuments documents;
    private final DomainBlocks blocks;
    private final ActorService actors;
    private final TargetFetchTurns turns;
    private final JsonMapper json;

    ForwardedNotes(
            SchedulerClient scheduler,
            StoredActivities stored,
            InboundActivities activities,
            ActivityDocuments documents,
            DomainBlocks blocks,
            ActorService actors,
            TargetFetchTurns turns,
            JsonMapper json) {
        this.scheduler = scheduler;
        this.stored = stored;
        this.activities = activities;
        this.documents = documents;
        this.blocks = blocks;
        this.actors = actors;
        this.turns = turns;
        this.json = json;
    }

    /**
     * Queues a fetch of the note, unless one is already queued for the same note, or for an {@code Update}, for the same
     * forwarded copy. Each {@code Update} gets a fetch of its own, since one that arrives while another's fetch is running
     * can be a newer edit than the one that fetch got.
     *
     * @param note        the activity's object, checked to be on the actor's server
     * @param type        {@code Create} or {@code Update}
     * @param forwardedId the forwarded copy's {@code id}, which only keeps apart the fetches of {@code Update}s
     * @param queuedAt    when the forwarded copy arrived, which has already waited in another job
     */
    void queue(URI note, URI actor, String type, String forwardedId, Instant queuedAt) {
        var job = new ForwardedNoteJob(note.toString(), actor.toString(), type, queuedAt, null);
        if (job.isUpdate()) {
            Jobs.schedule(scheduler, TASK, job, "forwarded-note", type, note.toString(), actor.toString(), forwardedId);
        } else {
            Jobs.schedule(scheduler, TASK, job, "forwarded-note", type, note.toString(), actor.toString());
        }
    }

    @Bean
    static Task<ForwardedNoteJob> forwardedNote(ObjectProvider<ForwardedNotes> forwardedNotes) {
        return Jobs.task(TASK, job -> forwardedNotes.getObject().run(job));
    }

    /**
     * Fetches the note once its host has a turn in {@link TargetFetchTurns}, since it comes from whoever forwarded the
     * activity, and stores the activity if the note is the actor's, and new for a {@code Create}. This has to be called with
     * no transaction open, because it waits on another server.
     *
     * @return what to do with the job, which is to wait for its turn if it hasn't come yet
     * @throws IllegalStateException if the note's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ForwardedNoteJob> run(ForwardedNoteJob job) {
        var note = URI.create(job.noteId());
        var type = job.isUpdate() ? "Update" : "Create";
        // The actor's URI hasn't been limited, and the ID would otherwise fail every retry, long after the fetch. The ID's
        // length doesn't depend on what it's made from.
        if (!InboundActivity.isStorableId(activityId(type, job.actorId(), ""))) {
            log.debug("Dropping a forwarded {} of {}, whose actor's URI is too long", type, note);
            return Jobs.done();
        }
        // A block can have been added since this was queued, and another forward of the note can have been stored.
        if (blocks.isBlocked(note.getHost())
                || (!job.isUpdate() && stored.exists(activityId(type, job.actorId(), note.toString())))) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(note, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Dropping a forwarded {} of {}, since too many fetches are waiting for its server", type, note);
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.waitUntil(turn.get(), job.withTurn(turn.get()));
        }
        try {
            fetch(note, job.actorId(), type, job.queuedAt());
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        return Jobs.done();
    }

    private void fetch(URI note, String actor, String type, Instant arrivedAt) {
        JsonNode document;
        try {
            document = documents.fetch(note);
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Dropping a forwarded {} of {}, which couldn't be fetched: {}", type, note, e.getMessage());
            return;
        }
        var read = RemoteNote.read(document).filter(found -> found.id().equals(note));
        if (read.isEmpty() || !read.get().attributedTo().equals(List.of(actor))) {
            log.debug("Dropping a forwarded {} of {}, which its server doesn't serve as a Note of {}", type, note, actor);
            return;
        }
        var published = read.get().published();
        if (type.equals("Create") && (published == null || published.isBefore(arrivedAt.minus(NEW_FOR)))) {
            log.debug("Dropping a forwarded Create of {}, which isn't new", note);
            return;
        }
        if (actors.isInactive(URI.create(actor))) {
            return;
        }
        var served = json.writeValueAsString(document);
        var id = activityId(type, actor, type.equals("Create") ? note.toString() : served);
        // No addressing of its own, so that only the note's decides who it's for.
        var built = new LinkedHashMap<String, Object>();
        built.put("@context", ActivityStreams.CONTEXT);
        built.put("id", id);
        built.put("type", type);
        built.put("actor", actor);
        built.put("object", document);
        var body = json.writeValueAsString(built);
        if (body.getBytes(StandardCharsets.UTF_8).length > Inbox.MAX_BODY_BYTES) {
            log.debug("Dropping a forwarded {} of {}, which is too large", type, note);
            return;
        }
        var activity = new InboundActivity(id, actor, type, body, arrivedAt);
        if (type.equals("Update")) {
            // A version that was applied before can still change what's stored, such as a poll's counts from an Update
            // that was handled before the status was stored.
            activities.acceptAgain(activity);
        } else {
            activities.accept(activity);
        }
    }

    /**
     * The ID that a note's activity is stored under, on the actor's server, as {@link Inbox} gives an activity that came
     * without one.
     *
     * @param from the note's ID for a {@code Create}, and what its server served for an {@code Update}
     */
    static String activityId(String type, String actor, String from) {
        return actor + "#forwarded-" + type.toLowerCase(Locale.ROOT) + "/" + Digests.sha256Hex(from);
    }
}
