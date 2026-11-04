package dev.biribiri.elysium.activitypub.inbound;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * The {@code object} of an activity, which can be sent as an ID, an embedded object, or an array of one of those. Only the
 * ID is known when it's sent as an ID. The parts of an embedded object are whatever its sender says they are, so nothing
 * here has been checked.
 *
 * @param id     its {@code id}, or null if it's embedded without one
 * @param type   its {@code type}, or the first one if it has several, or null if it isn't known
 * @param actor  the ID of its {@code actor}, or null if it isn't known
 * @param object the ID of its own {@code object}, or null if it isn't known
 */
record ActivityObject(String id, String type, String actor, String object) {

    /**
     * Can't match any actor, for when an embedded object has an {@code actor} but it isn't one ID.
     */
    private static final String NOT_ONE_ACTOR = "";

    /**
     * @param body the activity's JSON
     * @return the activity's {@code object}, or empty if it doesn't have just one with something to identify it
     */
    static Optional<ActivityObject> of(String body, JsonMapper json) {
        var node = json.readTree(body).path("object");
        if (node.isArray()) {
            if (node.size() != 1) {
                return Optional.empty();
            }
            node = node.path(0);
        }
        if (!node.isObject()) {
            var id = ActivityEnvelope.text(node);
            return id == null ? Optional.empty() : Optional.of(new ActivityObject(id, null, null, null));
        }
        var actorNode = node.path("actor");
        String actor = null;
        if (!actorNode.isMissingNode()) {
            var single = singleId(actorNode);
            actor = single == null ? NOT_ONE_ACTOR : single;
        }
        return Optional.of(new ActivityObject(
                ActivityEnvelope.text(node.path("id")),
                ActivityEnvelope.firstText(node.path("type")),
                actor,
                singleId(node.path("object"))));
    }

    /**
     * @return the URI, or empty if this isn't one
     */
    static Optional<URI> parseUri(String value) {
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * @return whether this could have come from the actor. An object that doesn't say who it's from can't be told apart.
     */
    boolean couldBeFrom(String actorUri) {
        return actor == null || actor.equals(actorUri);
    }

    private static String singleId(JsonNode node) {
        if (node.isArray()) {
            return node.size() == 1 ? ActivityEnvelope.idOf(node.path(0)) : null;
        }
        return ActivityEnvelope.idOf(node);
    }
}
