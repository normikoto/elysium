package dev.biribiri.elysium.activitypub.inbound;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The parts of an activity that an inbox needs in order to accept it. Activities are read as plain JSON, without a JSON-LD
 * processor, and no {@code @context} is ever fetched.
 *
 * @param id    the activity's {@code id}
 * @param type  the activity's {@code type}, or the first one if it has several
 * @param actor the ID of the activity's {@code actor}, which can be sent as an ID or as an object
 * @param body  the activity's JSON
 */
record ActivityEnvelope(String id, String type, String actor, String body) {

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
        var id = text(document.path("id"));
        var type = firstText(document.path("type"));
        var actor = idOf(document.path("actor"));
        if (id == null || type == null || actor == null) {
            return Optional.empty();
        }
        return Optional.of(new ActivityEnvelope(id, type, actor, body));
    }

    private static String firstText(JsonNode node) {
        if (!node.isArray()) {
            return text(node);
        }
        return node.valueStream().map(ActivityEnvelope::text).filter(value -> value != null).findFirst().orElse(null);
    }

    private static String idOf(JsonNode node) {
        return text(node.isObject() ? node.path("id") : node);
    }

    /**
     * @return the string, or null if the node isn't one that can be stored. A JSON string can spell out a NUL as an escape.
     */
    private static String text(JsonNode node) {
        var value = node.stringValue(null);
        return value == null || value.isEmpty() || value.indexOf('\0') != -1 ? null : value;
    }
}
