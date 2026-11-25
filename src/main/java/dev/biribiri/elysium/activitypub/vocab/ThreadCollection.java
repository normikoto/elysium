package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * The posts in a thread, as FEP-f228's collection of posts has them, for other servers to backfill the thread from. Unlike
 * other ordered collections, its pages are oldest first, as the FEP has them, so that a server reading them in order
 * gets each reply after its parent.
 *
 * @param attributedTo who started the thread, whose view of it this is
 */
public record ThreadCollection(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        URI attributedTo,
        long totalItems,
        URI first
) {

    public static ThreadCollection of(URI id, URI attributedTo, long totalItems, URI first) {
        return new ThreadCollection(ActivityStreams.CONTEXT, id, "OrderedCollection", attributedTo, totalItems, first);
    }
}
