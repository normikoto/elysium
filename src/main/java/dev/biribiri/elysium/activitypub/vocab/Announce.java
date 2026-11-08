package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Visibility;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * A boost, which names the status it boosts by ID only, so that the other server fetches it from its author's.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Announce(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        String published,
        List<String> to,
        List<String> cc,
        URI object
) {

    /**
     * A boost from here, addressed like a status of its visibility, with the boosted status's author too, so that its
     * server can count it.
     *
     * @param author the boosted status's author, or null if it's the booster's own
     */
    public static Announce of(String id, Actor booster, URI status, URI author, Visibility visibility, Instant published) {
        var audience = Audience.of(visibility, booster, author == null ? List.of() : List.of(author.toString()));
        return new Announce(ActivityStreams.CONTEXT, id, "Announce", booster.uri(), ActivityStreams.time(published), audience.to(),
                audience.cc(), status);
    }

    /**
     * A boost in a {@code shares} collection, which may be from another server, so it's only addressed to the public, as
     * the only part of who it was sent to that's known here.
     */
    public static Announce inCollection(String id, URI booster, URI status, Visibility visibility, Instant published) {
        var publicOnly = List.of(ActivityStreams.PUBLIC);
        return new Announce(null, id, "Announce", booster, ActivityStreams.time(published),
                visibility == Visibility.PUBLIC ? publicOnly : null,
                visibility == Visibility.UNLISTED ? publicOnly : null, status);
    }

    public Announce withoutContext() {
        return new Announce(null, id, type, actor, published, to, cc, object);
    }
}
