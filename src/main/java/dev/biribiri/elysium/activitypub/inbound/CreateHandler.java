package dev.biribiri.elysium.activitypub.inbound;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@code Note} from another server as a status. {@link ActorNotes} makes sure the note is the actor's own, and
 * {@link NoteStorage} decides whether it's for anyone here.
 */
@Component
class CreateHandler implements InboundActivityHandler {

    private final ActorNotes notes;
    private final NoteStorage storage;
    private final JsonMapper json;

    CreateHandler(ActorNotes notes, NoteStorage storage, JsonMapper json) {
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
        notes.read(activity, document).ifPresent(read -> storage.store(activity, document, read));
    }
}
