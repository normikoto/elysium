package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Visibility;

import java.net.URI;
import java.util.List;

/**
 * An {@code Add} or {@code Remove} of a status in its author's {@code featured} collection, which is how a pin is sent.
 * Mastodon's have no {@code id} or addressing, but these have both, since other servers read them either way, and an
 * {@code id} lets a {@code Remove} be queued after its {@code Add}. They're addressed like the status, so that a server
 * reading the addressing doesn't take a pinned followers-only status for a public one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionChange(
        @JsonProperty("@context") String context,
        String id,
        String type,
        URI actor,
        URI object,
        URI target,
        List<String> to,
        List<String> cc
) {

    public static CollectionChange pin(String id, Actor actor, URI status, Visibility visibility) {
        return of(id, "Add", actor, status, visibility);
    }

    public static CollectionChange unpin(String id, Actor actor, URI status, Visibility visibility) {
        return of(id, "Remove", actor, status, visibility);
    }

    /**
     * @return the ID of the {@code Remove} that undoes an {@code Add} with this ID
     */
    public static String removalIdFor(String addId) {
        return addId + "/remove";
    }

    private static CollectionChange of(String id, String type, Actor actor, URI status, Visibility visibility) {
        var audience = Audience.of(visibility, actor, List.of());
        return new CollectionChange(ActivityStreams.CONTEXT, id, type, actor.uri(), status, Person.featuredOf(actor),
                audience.to(), audience.cc());
    }
}
