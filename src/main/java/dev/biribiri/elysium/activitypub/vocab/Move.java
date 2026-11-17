package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Tells a local account's followers' servers that it has moved, as FEP-7628 and Mastodon do, so that they can move its
 * followers to {@code target}. It's addressed to the followers, as FEP-7628 asks, though Mastodon leaves that out.
 *
 * @param object the account that moved, which is also the {@code actor}
 */
public record Move(
        @JsonProperty("@context") String context,
        URI id,
        String type,
        URI actor,
        URI object,
        URI target,
        List<URI> to
) {

    /**
     * @param at when it moved, since an account can move more than once, and each needs its own ID
     */
    public static Move of(Actor actor, URI target, Instant at) {
        return new Move(
                ActivityStreams.CONTEXT,
                URI.create(actor.uri() + "#moves/" + at.toEpochMilli()),
                "Move",
                actor.uri(),
                actor.uri(),
                target,
                List.of(Person.followersOf(actor)));
    }
}
