package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Visibility;

import java.net.URI;
import java.util.List;

/**
 * Tells other servers to remove a status they were sent.
 */
public record Delete(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        List<String> cc,
        Tombstone object
) {

    /**
     * @param visibility the status's visibility, so that this is addressed in the same way as its {@code Create}
     * @throws IllegalArgumentException if the status was direct
     */
    public static Delete of(URI statusUri, Actor author, Visibility visibility) {
        var audience = Audience.of(visibility, author, List.of());
        return new Delete(
                ActivityStreams.CONTEXT,
                URI.create(statusUri + "#delete"),
                "Delete",
                author.uri(),
                audience.to(),
                audience.cc(),
                Tombstone.inActivity(statusUri));
    }
}
