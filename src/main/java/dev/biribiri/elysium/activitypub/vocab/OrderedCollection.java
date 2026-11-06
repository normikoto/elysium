package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * A collection, which links to the first page of its items, or only says how many it has.
 *
 * @param first the first page, or null when the items aren't shown
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderedCollection(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        long totalItems,
        URI first
) {

    public static OrderedCollection ofSize(URI id, long totalItems) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", totalItems, null);
    }

    public static OrderedCollection paged(URI id, long totalItems, URI first) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", totalItems, first);
    }
}
