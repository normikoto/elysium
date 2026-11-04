package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * A collection that only says how many items it has.
 */
public record OrderedCollection(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        long totalItems
) {

    public static OrderedCollection ofSize(URI id, long totalItems) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", totalItems);
    }
}
