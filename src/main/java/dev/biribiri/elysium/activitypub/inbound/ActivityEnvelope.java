package dev.biribiri.elysium.activitypub.inbound;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

/**
 * The parts of an activity that an inbox needs in order to accept it. Activities are read as plain JSON, without a JSON-LD
 * processor, and no {@code @context} is ever fetched.
 *
 * @param id    the activity's {@code id}, or null for an {@code Add} or {@code Remove} without one, as Mastodon and Misskey
 *              send them
 * @param type  the activity's {@code type}, or the first one if it has several
 * @param actor the ID of the activity's {@code actor}, which can be sent as an ID, an object, or an array of one of those.
 *              This is null if there are several, since one signature can't speak for all of them.
 * @param body  the activity's JSON
 */
record ActivityEnvelope(String id, String type, String actor, String body) {

    /** The types of activity that are accepted without an {@code id}, since common servers send them that way. */
    private static final Set<String> ID_OPTIONAL = Set.of("Add", "Remove");

    /**
     * @return the activity, or empty if the body isn't one
     */
    static Optional<ActivityEnvelope> parse(byte[] bytes, JsonMapper json) {
        String body;
        JsonNode document;
        try {
            // Decoded here, because parsing the bytes would also accept UTF-16. The result would contain NUL characters,
            // which the database refuses, and the job would fail again every time it's retried.
            body = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            document = json.readTree(body);
        } catch (CharacterCodingException | JacksonException e) {
            return Optional.empty();
        }
        if (!document.isObject()) {
            return Optional.empty();
        }
        var id = ActivityJson.text(document.path("id"));
        var type = ActivityJson.firstText(document.path("type"));
        var actorNode = document.path("actor");
        var hasSeveralActors = actorNode.isArray() && actorNode.size() > 1;
        var actor = hasSeveralActors ? null : ActivityJson.idOf(actorNode.isArray() ? actorNode.path(0) : actorNode);
        if (type == null || (id == null && !ID_OPTIONAL.contains(type)) || (actor == null && !hasSeveralActors)) {
            return Optional.empty();
        }
        return Optional.of(new ActivityEnvelope(id, type, actor, body));
    }
}
