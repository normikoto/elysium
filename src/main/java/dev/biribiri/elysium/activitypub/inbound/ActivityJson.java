package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the different forms that ActivityStreams allows for the same data, from plain JSON. There's no JSON-LD processor, so
 * properties are matched by their usual short names, and a server that renames them with its own {@code @context} is
 * misread. Values that can't be stored, such as an empty string or one with a NUL, are skipped.
 */
final class ActivityJson {

    /** The other ways that servers write the public collection. */
    private static final Set<String> PUBLIC_ALIASES = Set.of("as:Public", "Public");

    private ActivityJson() {
    }

    /**
     * @return the string, or null if the node isn't one that can be stored. A JSON string can spell out a NUL as an escape.
     */
    static String text(JsonNode node) {
        var value = node.stringValue(null);
        return value == null || value.isEmpty() || value.indexOf('\0') != -1 ? null : value;
    }

    /**
     * @return the ID of a node that's an ID or an object with one, or null if it's neither
     */
    static String idOf(JsonNode node) {
        return text(node.isObject() ? node.path("id") : node);
    }

    /**
     * @return the first string of a node that's a string or an array, or null if it has none
     */
    static String firstText(JsonNode node) {
        if (!node.isArray()) {
            return text(node);
        }
        return node.valueStream().map(ActivityJson::text).filter(value -> value != null).findFirst().orElse(null);
    }

    /**
     * For properties such as {@code to} and {@code attributedTo}, which can each be one value or an array, of IDs or of
     * objects with one.
     *
     * @return the IDs, in order and without repeats
     */
    static List<String> ids(JsonNode node) {
        var ids = new LinkedHashSet<String>();
        if (node.isArray()) {
            node.valueStream().map(ActivityJson::idOf).filter(id -> id != null).forEach(ids::add);
        } else {
            var id = idOf(node);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * @return every {@code type}, from one value or an array
     */
    static List<String> types(JsonNode node) {
        if (!node.isArray()) {
            var type = text(node);
            return type == null ? List.of() : List.of(type);
        }
        return node.valueStream().map(ActivityJson::text).filter(type -> type != null).distinct().toList();
    }

    /**
     * @return the address, with the public collection always written out in full
     */
    static String audienceId(String id) {
        return PUBLIC_ALIASES.contains(id) ? ActivityStreams.PUBLIC : id;
    }

    /**
     * Reads a natural language property such as {@code content}, which can instead be sent only in {@code contentMap}, under
     * each language. The plain value comes first. Otherwise, a value with no language ({@code und}) is preferred, and then the
     * first one in the document.
     *
     * @param object the object to read it from
     * @param name   the property, such as {@code content} or {@code summary}
     * @return the text, or null if there's none
     */
    static String naturalText(JsonNode object, String name) {
        var plain = text(object.path(name));
        if (plain != null) {
            return plain;
        }
        var map = object.path(name + "Map");
        if (!map.isObject()) {
            return null;
        }
        var undetermined = text(map.path("und"));
        if (undetermined != null) {
            return undetermined;
        }
        return map.properties().stream()
                .map(Map.Entry::getValue)
                .map(ActivityJson::text)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    /**
     * @return whether the property is {@code true}. Anything else, including a missing one, is false.
     */
    static boolean flag(JsonNode node) {
        return node.isBoolean() && node.booleanValue();
    }

    /**
     * Reads {@code tag}, which can be one value or an array. Only objects are kept, because a tag sent as just an ID doesn't
     * say what it is.
     */
    static List<Tag> tags(JsonNode node) {
        var tags = new ArrayList<Tag>();
        for (var tag : node.isArray() ? node.valueStream().toList() : List.of(node)) {
            if (tag.isObject()) {
                tags.add(new Tag(firstText(tag.path("type")), idOf(tag.path("href")), text(tag.path("name"))));
            }
        }
        return List.copyOf(tags);
    }

    /**
     * One item of {@code tag}, such as a {@code Mention} or a {@code Hashtag}. Any part can be null.
     *
     * @param type its first {@code type}
     * @param href what it links to, such as the mentioned actor
     * @param name what it says, such as {@code @alice@example.com} or {@code #tag}
     */
    record Tag(String type, String href, String name) {
    }
}
