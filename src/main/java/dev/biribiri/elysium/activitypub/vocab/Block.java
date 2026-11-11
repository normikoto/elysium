package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;

/**
 * A local account's block of an actor on another server, which is only sent to that actor, so that its server can stop
 * showing it the account's posts and stop it from following again.
 *
 * @param context null when this is part of an {@code Undo}, which has the {@code @context} for both
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Block(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        URI object,
        List<URI> to
) {

    /**
     * @param id comes from the block, so that blocking again sends a new one
     */
    public static Block of(String id, URI blocker, URI blocked) {
        return new Block(ActivityStreams.CONTEXT, id, "Block", blocker, blocked, List.of(blocked));
    }

    Block withoutContext() {
        return new Block(null, id, type, actor, object, to);
    }
}
