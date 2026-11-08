package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/**
 * One page of a collection's items, newest first.
 *
 * @param next the page after this one, or null if this is the last
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderedCollectionPage<T>(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        long totalItems,
        URI partOf,
        URI next,
        List<T> orderedItems
) {

    public static <T> OrderedCollectionPage<T> of(URI id, long totalItems, URI partOf, URI next, List<T> items) {
        return new OrderedCollectionPage<>(
                ActivityStreams.CONTEXT, id, "OrderedCollectionPage", totalItems, partOf, next, items);
    }
}
