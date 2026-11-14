package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Reading a collection that a remote actor's server serves for it, such as its outbox or {@code featured} collection. What
 * such a collection embeds is trusted as that server's word, so it has to be fetched from the actor's own server, with
 * redirects not followed, and have the ID it was fetched from.
 */
final class ActorCollections {

    private ActorCollections() {
    }

    /**
     * @return whether the document is one of these types, with the ID it was fetched from, so that it's what the actor's
     * server serves at that URL rather than a copy of something else
     */
    static boolean hasId(JsonNode document, URI fetched, String... types) {
        var found = ActivityJson.types(document.path("type"));
        return fetched.toString().equals(ActivityJson.text(document.path("id")))
                && Arrays.stream(types).anyMatch(found::contains);
    }

    /**
     * @return up to this many of a collection's or page's items, in order
     */
    static List<JsonNode> items(JsonNode page, int max) {
        var items = page.has("orderedItems") ? page.path("orderedItems") : page.path("items");
        if (items.isMissingNode() || items.isNull()) {
            return List.of();
        }
        return items.isArray() ? StreamSupport.stream(items.spliterator(), false).limit(max).toList() : List.of(items);
    }

    /**
     * The actor's server serves what it says is at these, so they're the only IDs that are fetched on its word.
     */
    static Optional<URI> idOnActorsServer(JsonNode node, Actor actor) {
        return Optional.ofNullable(ActivityJson.text(node))
                .filter(InboundActivity::isStorableId)
                .flatMap(ActivityObject::parseUri)
                .filter(id -> Inbox.isOnActorsServer(id, actor.uri()));
    }

    /**
     * Whether the note is attributed to the actor alone, with an ID on its server that could be stored.
     */
    static boolean isActors(RemoteNote note, Actor actor) {
        return note.attributedTo().equals(List.of(actor.uri().toString()))
                && InboundActivity.isStorableId(note.id().toString())
                && Inbox.isOnActorsServer(note.id(), actor.uri());
    }
}
