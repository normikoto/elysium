package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Stores a {@code Note} from another server as a status. {@link ActorNotes} makes sure the note is the actor's own, and
 * {@link NoteStorage} decides whether it's for anyone here. One that's a vote in a local poll goes to {@link PollVotes}
 * instead. A reply whose parent isn't stored has its parents fetched through {@link ThreadFetches}, or, if it isn't for
 * anyone here, waits for {@link ReplyParentChecks} to find its parent.
 */
@Component
class CreateHandler implements InboundActivityHandler {

    private final ActorNotes notes;
    private final NoteStorage storage;
    private final PollVotes votes;
    private final ThreadFetches threads;
    private final ReplyParentChecks replyParents;
    private final JsonMapper json;

    CreateHandler(
            ActorNotes notes, NoteStorage storage, PollVotes votes, ThreadFetches threads, ReplyParentChecks replyParents,
            JsonMapper json) {
        this.replyParents = replyParents;
        this.votes = votes;
        this.threads = threads;
        this.notes = notes;
        this.storage = storage;
        this.json = json;
    }

    @Override
    public String type() {
        return "Create";
    }

    @Override
    public void handle(InboundActivity activity) {
        var document = json.readTree(activity.body());
        var read = notes.read(activity, document);
        if (read.isEmpty() || votes.receive(activity, read.get().note())) {
            return;
        }
        // Also when it's stored already, since a retry of this activity finds it so, and the try that stored it could have
        // failed before queuing the fetch.
        switch (storage.store(activity, document, read.get())) {
            case STORED, TAKEN -> threads.fetchParentsOf(read.get().note(), URI.create(activity.actorUri()));
            case WAITING_FOR_PARENT -> replyParents.queue(activity, read.get().note().inReplyTo());
            case IGNORED -> {
            }
        }
    }
}
