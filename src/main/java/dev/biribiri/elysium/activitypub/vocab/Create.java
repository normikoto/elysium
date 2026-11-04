package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/**
 * Tells other servers about a new status, which is included so that they don't have to fetch it. They couldn't fetch one for
 * followers only.
 */
public record Create(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI actor,
        String published,
        List<String> to,
        List<String> cc,
        Note object
) {

    public static Create of(Note note) {
        return new Create(
                Note.CONTEXT,
                URI.create(idFor(note.id())),
                "Create",
                note.attributedTo(),
                note.published(),
                note.to(),
                note.cc(),
                note.withoutContext());
    }

    /**
     * @return the {@code id} of the activity for a status, which can be worked out again to cancel it
     */
    public static String idFor(URI statusUri) {
        return statusUri + "#create";
    }
}
