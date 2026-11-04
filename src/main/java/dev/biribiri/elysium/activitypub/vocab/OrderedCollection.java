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

    private static final String CONTEXT = "https://www.w3.org/ns/activitystreams";

    public static OrderedCollection ofSize(URI id, long totalItems) {
        return new OrderedCollection(CONTEXT, id, "OrderedCollection", totalItems);
    }
}
