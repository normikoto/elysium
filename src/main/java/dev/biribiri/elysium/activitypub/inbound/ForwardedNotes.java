package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.Digests;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Stores the note of a forwarded {@code Create} that its origin doesn't serve, once the note's own server serves it as the
 * actor's. That's the server that speaks for the note, so what it serves is stored as a {@code Create} of its own, and
 * nothing from the forwarded copy is kept but the note's ID. The note's addressing decides who it's for.
 *
 * <p>That {@code Create} isn't stored under the forwarded copy's {@code id}, which nobody has vouched for. Otherwise a server
 * could forward one that gives a real {@code Create}'s {@code id} to another of its actor's notes, and the real one would
 * be dropped as a repeat. Its ID comes from the note instead, so forwards of the same note are repeats of each other, and
 * the real {@code Create} still arrives under its own, finding the note already stored.
 */
@Component
class ForwardedNotes {

    static final TaskDescriptor<ForwardedNoteJob> TASK = TaskDescriptor.of("forwarded-note", ForwardedNoteJob.class);

    /**
     * How long before it was forwarded a note can have been published. Anyone can forward a {@code Create}, so without this
     * a server could have an actor's old notes stored here as new, where they'd go to the top of timelines and notify. A
     * {@code Create} is forwarded when it's delivered, which is within this of its note being posted.
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
     * Queues a fetch of the note, unless one for the same note is already queued.
     *
     * @param note     the {@code Create}'s object, checked to be on the actor's server
     * @param queuedAt when the forwarded copy arrived, which has already waited in another job
     */
    void queue(URI note, URI actor, Instant queuedAt) {
        Jobs.schedule(scheduler, TASK, new ForwardedNoteJob(note.toString(), actor.toString(), queuedAt, null),
                "forwarded-note", note.toString(), actor.toString());
    }

    /**
     * Fetches the note once its host has a turn in {@link TargetFetchTurns}, since it comes from whoever forwarded the
     * {@code Create}, and stores it if it's the actor's and new. This has to be called with no transaction open, because it
     * waits on another server.
     *
     * @return what to do with the job, which is to wait for its turn if it hasn't come yet
     * @throws IllegalStateException if the note's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ForwardedNoteJob> run(ForwardedNoteJob job) {
        var note = URI.create(job.noteId());
        var id = createId(job.actorId(), note);
        // A block can have been added since this was queued, and another forward of the note can have been stored.
        if (blocks.isBlocked(note.getHost()) || stored.exists(id)) {
            return Jobs.done();
        }
        var now = Instant.now();
        var turn = turns.take(note, job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Dropping a forwarded Create of {}, since too many fetches are waiting for its server", note);
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.waitUntil(turn.get(), job.withTurn(turn.get()));
        }
        fetch(note, job.actorId(), id, job.queuedAt());
        return Jobs.done();
    }

    private void fetch(URI note, String actor, String id, Instant arrivedAt) {
        JsonNode document;
        try {
            document = documents.fetch(note);
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Dropping a forwarded Create of {}, which couldn't be fetched: {}", note, e.getMessage());
            return;
        }
        var read = RemoteNote.read(document).filter(found -> found.id().equals(note));
        if (read.isEmpty() || !read.get().attributedTo().equals(List.of(actor))) {
            log.debug("Dropping a forwarded Create of {}, which its server doesn't serve as a Note of {}", note, actor);
            return;
        }
        var published = read.get().published();
        if (published == null || published.isBefore(arrivedAt.minus(NEW_FOR))) {
            log.debug("Dropping a forwarded Create of {}, which isn't new", note);
            return;
        }
        if (actors.isInactive(URI.create(actor))) {
            return;
        }
        // No addressing of its own, so that only the note's decides who it's for.
        var create = new LinkedHashMap<String, Object>();
        create.put("@context", ActivityStreams.CONTEXT);
        create.put("id", id);
        create.put("type", "Create");
        create.put("actor", actor);
        create.put("object", document);
        var body = json.writeValueAsString(create);
        if (body.getBytes(StandardCharsets.UTF_8).length > Inbox.MAX_BODY_BYTES) {
            log.debug("Dropping a forwarded Create of {}, which is too large", note);
            return;
        }
        activities.accept(new InboundActivity(id, actor, "Create", body, arrivedAt));
    }

    /**
     * The ID that the note's {@code Create} is stored under, on the actor's server, as {@link Inbox} gives an activity
     * that came without one.
     */
    static String createId(String actor, URI note) {
        return actor + "#forwarded-create/" + Digests.sha256Hex(note.toString());
    }
}
