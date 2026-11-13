package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@code Note} from another server as a status. {@link ActorNotes} makes sure the note is the actor's own, and
 * {@link NoteStorage} decides whether it's for anyone here. One that's a vote in a local poll goes to {@link PollVotes}
 * instead.
 */
@Component
class CreateHandler implements InboundActivityHandler {

    private final ActorNotes notes;
    private final NoteStorage storage;
    private final PollVotes votes;
    private final JsonMapper json;

    CreateHandler(ActorNotes notes, NoteStorage storage, PollVotes votes, JsonMapper json) {
        this.votes = votes;
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
        storage.store(activity, document, read.get());
    }
}
