package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/**
 * Tells other servers about a new status, which is included so that they don't have to fetch it. They couldn't fetch one for
 * followers only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
     * For a collection's page, whose own {@code @context} covers it.
     */
    public Create withoutContext() {
        return new Create(null, id, type, actor, published, to, cc, object);
    }

    /**
     * @return the {@code id} of the activity for a status, which can be worked out again to cancel it
     */
    public static String idFor(URI statusUri) {
        return statusUri + "#create";
    }
}
