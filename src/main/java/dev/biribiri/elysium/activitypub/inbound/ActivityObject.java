package dev.biribiri.elysium.activitypub.inbound;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * The {@code object} of an activity, which can be sent as an ID, an embedded object, or an array of one of those. Only the
 * ID is known when it's sent as an ID, and the rest is then null or empty. The parts of an embedded object are whatever its
 * sender says they are, so nothing here has been checked. {@link ActivityJson} says how each one is read.
 *
 * @param id           its {@code id}, or null if it's embedded without one
 * @param type         its {@code type}, or the first one if it has several, or null if it isn't known
 * @param actor        the ID of its {@code actor}, or null if it isn't known
 * @param object       the ID of its own {@code object}, or null if it isn't known
 * @param attributedTo the IDs in its {@code attributedTo}
 * @param content      its {@code content}, or the one in {@code contentMap}, or null if it has none
 * @param summary      its {@code summary}, or the one in {@code summaryMap}, or null if it has none
 * @param sensitive    whether {@code sensitive} is true
 * @param tags         what's in its {@code tag}
 * @param addressing   who it's addressed to, which can differ from its activity's
 */
record ActivityObject(
        String id,
        String type,
        String actor,
        String object,
        List<String> attributedTo,
        String content,
        String summary,
        boolean sensitive,
        List<ActivityJson.Tag> tags,
        Addressing addressing) {

    ActivityObject {
        attributedTo = List.copyOf(attributedTo);
        tags = List.copyOf(tags);
    }

    /**
     * Can't match any actor, for when an embedded object has an {@code actor} but it isn't one ID.
     */
    private static final String NOT_ONE_ACTOR = "";

    /**
     * @param body the activity's JSON
     * @return the activity's {@code object}, or empty if it doesn't have just one with something to identify it
     */
    static Optional<ActivityObject> of(String body, JsonMapper json) {
        return of(json.readTree(body));
    }

    /**
     * @param activity the activity's JSON, for a caller that has already parsed it
     * @return the activity's {@code object}, or empty if it doesn't have just one with something to identify it
     */
    static Optional<ActivityObject> of(JsonNode activity) {
        var node = activity.path("object");
        if (node.isArray()) {
            if (node.size() != 1) {
                return Optional.empty();
            }
            node = node.path(0);
        }
        if (!node.isObject()) {
            var id = ActivityJson.text(node);
            return id == null ? Optional.empty() : Optional.of(onlyId(id));
        }
        var actorNode = node.path("actor");
        String actor = null;
        if (!actorNode.isMissingNode()) {
            var single = singleId(actorNode);
            actor = single == null ? NOT_ONE_ACTOR : single;
        }
        return Optional.of(new ActivityObject(
                ActivityJson.text(node.path("id")),
                ActivityJson.firstText(node.path("type")),
                actor,
                singleId(node.path("object")),
                ActivityJson.ids(node.path("attributedTo")),
                ActivityJson.naturalText(node, "content"),
                ActivityJson.naturalText(node, "summary"),
                ActivityJson.flag(node.path("sensitive")),
                ActivityJson.tags(node.path("tag")),
                Addressing.of(node)));
    }

    private static ActivityObject onlyId(String id) {
        return new ActivityObject(id, null, null, null, List.of(), null, null, false, List.of(), Addressing.NONE);
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
            return node.size() == 1 ? ActivityJson.idOf(node.path(0)) : null;
        }
        return ActivityJson.idOf(node);
    }
}
