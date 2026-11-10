package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.Focus;
import dev.biribiri.elysium.content.InvalidMediaAttachmentException;
import dev.biribiri.elysium.content.MediaChange;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads an edit's {@code media_attributes}, which a form sends the way Rails reads arrays of hashes: as
 * {@code media_attributes[][id]}, with the values of each field in order, or as {@code media_attributes[0][id]}.
 */
final class MediaAttributes {

    /**
     * An index is at most a few digits, so that one too large for an int is refused like any other unknown name.
     */
    private static final Pattern FORM_NAME = Pattern.compile("media_attributes\\[(\\d{0,4})]\\[(\\w+)]");

    private MediaAttributes() {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "MediaAttributes")
    record Body(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One of the status's attachments.")
            String id,
            @Schema(description = "What it shows, for people who can't see it. Empty removes it.")
            String description,
            @Schema(description = "Where to keep in view when it's cropped, as x,y from -1 to 1.")
            String focus) {
    }

    /**
     * @return each attachment's values, as JSON would have them
     */
    static List<Map<String, Object>> fromForm(MultiValueMap<String, String> form) {
        var indexed = new TreeMap<Integer, Map<String, Object>>();
        var unindexed = new ArrayList<Map<String, Object>>();
        form.forEach((name, values) -> {
            var matcher = FORM_NAME.matcher(name);
            if (!matcher.matches()) {
                return;
            }
            var field = matcher.group(2);
            if (matcher.group(1).isEmpty()) {
                for (var i = 0; i < values.size(); i++) {
                    while (unindexed.size() <= i) {
                        unindexed.add(new HashMap<>());
                    }
                    unindexed.get(i).put(field, values.get(i));
                }
            } else {
                indexed.computeIfAbsent(Integer.parseInt(matcher.group(1)), index -> new HashMap<>())
                        .put(field, values.getFirst());
            }
        });
        var all = new ArrayList<Map<String, Object>>(indexed.values());
        all.addAll(unindexed);
        return all;
    }

    static List<MediaChange> read(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> entries)) {
            throw new InvalidRequestException("Validation failed: media_attributes must be a list");
        }
        var changes = new ArrayList<MediaChange>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> attributes)) {
                throw new InvalidRequestException("Validation failed: media_attributes must be a list");
            }
            @SuppressWarnings("unchecked")
            var values = (Map<String, ?>) attributes;
            if (RequestParameters.string(values, "thumbnail") != null) {
                throw new InvalidRequestException("Validation failed: thumbnail is not supported in media_attributes");
            }
            var id = RequestParameters.uuid(RequestParameters.string(values, "id")).orElseThrow(() ->
                    new InvalidRequestException("Validation failed: media_attributes needs the ID of an attachment"));
            // Unlike other strings, an empty description is a value, which removes the one there is.
            var description = values.get("description") instanceof String string ? string : null;
            if (values.get("description") != null && description == null) {
                throw new InvalidRequestException("Validation failed: description must be a string");
            }
            try {
                var focus = Focus.parse(RequestParameters.string(values, "focus")).orElse(null);
                changes.add(new MediaChange(id, description, focus));
            } catch (InvalidMediaAttachmentException e) {
                throw new InvalidRequestException("Validation failed: " + e.getMessage());
            }
        }
        return changes;
    }
}
