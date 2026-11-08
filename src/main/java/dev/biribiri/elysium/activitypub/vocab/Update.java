package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Tells other servers that an actor has changed, its profile or whether it's suspended, or that a status has been edited.
 *
 * @param object a {@link Person} or a {@link Note}
 */
public record Update(
        @JsonProperty("@context") List<?> context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        List<String> cc,
        Object object
) {

    /**
     * @param person   the actor's document as it is now
     * @param occasion what's changed
     * @param at       when, because the same thing can change more than once, and each needs its own ID
     */
    public static Update of(Person person, String occasion, Instant at) {
        return new Update(
                person.context(),
                URI.create(person.id() + "#" + occasion + "/" + at.toEpochMilli()),
                "Update",
                person.id(),
                List.of(ActivityStreams.PUBLIC),
                List.of(person.followers().toString()),
                person.withoutContext());
    }

    /**
     * @param note     the status as it is now
     * @param editedAt when it was edited, because it can be edited more than once, and each edit needs its own ID
     */
    public static Update of(Note note, Instant editedAt) {
        return new Update(
                Note.CONTEXT,
                URI.create(idPrefixFor(note.id()) + editedAt.toEpochMilli()),
                "Update",
                note.attributedTo(),
                note.to(),
                note.cc(),
                note.withoutContext());
    }

    /**
     * @return what the {@code id} of each edit's activity for a status starts with, so that they can be cancelled together
     */
    public static String idPrefixFor(URI statusUri) {
        return statusUri + "#updates/";
    }
}
