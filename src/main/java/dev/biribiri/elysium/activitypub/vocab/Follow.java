package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * A request from a local account to follow an actor on another server.
 *
 * @param context null when this is part of an {@code Undo}, which has the {@code @context} for both
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Follow(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        URI object
) {

    /**
     * @param id comes from the follow, so that a new request has a new one
     */
    public static Follow of(String id, URI follower, URI followed) {
        return new Follow(ActivityStreams.CONTEXT, id, "Follow", follower, followed);
    }

    Follow withoutContext() {
        return new Follow(null, id, type, actor, object);
    }
}
