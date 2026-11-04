package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.DeletedStatus;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * What's in place of a deleted status.
 *
 * @param context null when this is part of an activity, which has the {@code @context} for both
 * @param deleted when it was deleted, or null when this is part of an activity, which doesn't need it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tombstone(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        String formerType,
        String deleted
) {

    public static Tombstone from(DeletedStatus status) {
        return new Tombstone(
                ActivityStreams.CONTEXT,
                status.uri(),
                "Tombstone",
                "Note",
                DateTimeFormatter.ISO_INSTANT.format(status.deletedAt().truncatedTo(ChronoUnit.SECONDS)));
    }

    static Tombstone inActivity(URI statusUri) {
        return new Tombstone(null, statusUri, "Tombstone", "Note", null);
    }
}
