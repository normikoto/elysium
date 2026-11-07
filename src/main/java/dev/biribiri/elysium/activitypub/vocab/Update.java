package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Tells other servers that an actor has changed: its profile, or whether it's suspended.
 */
public record Update(
        @JsonProperty("@context") List<?> context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        List<String> cc,
        Person object
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
}
