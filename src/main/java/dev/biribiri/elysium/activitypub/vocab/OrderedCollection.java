package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/**
 * A collection, which links to the first page of its items, lists them all, or only says how many it has.
 *
 * @param first        the first page, or null when the items aren't paged
 * @param orderedItems every item, for a collection small enough not to be paged, or null when they aren't shown here
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderedCollection(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        long totalItems,
        URI first,
        List<?> orderedItems
) {

    public static OrderedCollection ofSize(URI id, long totalItems) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", totalItems, null, null);
    }

    public static OrderedCollection paged(URI id, long totalItems, URI first) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", totalItems, first, null);
    }

    /**
     * Mastodon and Misskey serve a {@code featured} collection like this, and read theirs without following pages.
     */
    public static OrderedCollection of(URI id, List<?> items) {
        return new OrderedCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", items.size(), null, items);
    }
}
