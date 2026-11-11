package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.content.QuotePolicy;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads a request to {@code update_credentials}, from a form or JSON, into what it changes.
 *
 * <p>Clients send every setting on their edit screen whenever someone saves their profile, including ones this server
 * doesn't have. Those are accepted when they ask for what the server already does, and refused otherwise, rather than
 * ignored, so that a client doesn't tell someone a setting was saved. An empty value counts as missing for them, because
 * form clients send fields they haven't filled in. For {@code display_name} and {@code note}, an empty value clears them,
 * as on Mastodon.
 *
 * @param hideCollections null to leave it as it is
 * @param quotePolicy     the default for new statuses, or null to leave it as it is
 */
record CredentialsRequest(ProfileChange profile, Boolean hideCollections, QuotePolicy quotePolicy) {

    /**
     * Settings that don't exist here, with the value that's reported for them.
     */
    private static final Map<String, Object> FIXED = Map.of(
            "bot", false,
            "discoverable", false,
            "indexable", false);

    private static final Map<String, Object> FIXED_SOURCE = Map.of(
            "privacy", "public",
            "sensitive", false);

    private static final String FIELDS = "fields_attributes";

    /**
     * {@code fields_attributes[0][name]}, or {@code fields_attributes[][name]} for a list.
     */
    private static final Pattern FORM_FIELD = Pattern.compile("fields_attributes\\[(\\d*)]\\[(name|value)]");
    private static final Pattern FORM_SOURCE = Pattern.compile("source\\[(\\w+)]");

    /**
     * A form's fields and {@code source} settings are each sent as several keys, which are gathered up the way they'd be
     * nested in JSON.
     */
    static CredentialsRequest fromForm(MultiValueMap<String, String> form, Map<String, MultipartFile> files) {
        var values = new LinkedHashMap<String, Object>();
        var indexed = new TreeMap<Integer, Map<String, Object>>();
        var listed = new LinkedHashMap<String, List<String>>();
        var source = new LinkedHashMap<String, Object>();
        form.forEach((key, list) -> {
            var last = list.isEmpty() ? null : list.getLast();
            var field = FORM_FIELD.matcher(key);
            var setting = FORM_SOURCE.matcher(key);
            if (field.matches() && field.group(1).isEmpty()) {
                listed.put(field.group(2), list);
            } else if (field.matches()) {
                indexed.computeIfAbsent(index(field.group(1)), i -> new LinkedHashMap<>()).put(field.group(2), last);
            } else if (setting.matches()) {
                source.put(setting.group(1), last);
            } else {
                values.put(key, last);
            }
        });
        if (!indexed.isEmpty() || !listed.isEmpty()) {
            var fields = new ArrayList<Object>(indexed.values());
            for (var field : ProfileChange.NewField.zip(
                    listed.getOrDefault("name", List.of()), listed.getOrDefault("value", List.of()))) {
                fields.add(Map.of("name", field.name(), "value", field.value()));
            }
            values.put(FIELDS, fields);
        }
        if (!source.isEmpty()) {
            values.put("source", source);
        }
        return read(values, files);
    }

    static CredentialsRequest fromJson(Map<String, ?> body) {
        return read(body, Map.of());
    }

    private static CredentialsRequest read(Map<String, ?> values, Map<String, MultipartFile> files) {
        var change = ProfileChange.NONE;
        Boolean hide = null;
        QuotePolicy quotePolicy = null;
        for (var entry : values.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            switch (key) {
                case "display_name" -> change = change.withDisplayName(text(key, value));
                case "note" -> change = change.withNote(text(key, value));
                case FIELDS -> change = change.withFields(fields(value));
                case "hide_collections" -> {
                    if (!isEmpty(value)) {
                        hide = RequestParameters.flag(values, key);
                    }
                }
                case "locked" -> {
                    if (!isEmpty(value)) {
                        change = change.withManuallyApprovesFollowers(RequestParameters.flag(values, key));
                    }
                }
                case "avatar", "header" -> {
                    if (!isEmpty(value)) {
                        throw new InvalidRequestException("Validation failed: " + key + " has to be a file in a multipart form");
                    }
                }
                case "source" -> quotePolicy = source(value);
                default -> checkFixed(key, value, FIXED, key);
            }
        }
        change = change.withAvatar(image(files.get("avatar"))).withHeader(image(files.get("header")));
        return new CredentialsRequest(change, hide, quotePolicy);
    }

    private static String text(String key, Object value) {
        return switch (value) {
            case null -> "";
            case String string -> string;
            default -> throw new InvalidRequestException("Validation failed: " + key + " must be a string");
        };
    }

    /**
     * JSON clients send the fields as a list, or as an object keyed by position like a form's, which are put in order.
     */
    private static List<ProfileChange.NewField> fields(Object value) {
        List<?> entries = switch (value) {
            case null -> List.of();
            case String string when string.isEmpty() -> List.of();
            case List<?> list -> list;
            case Map<?, ?> map -> {
                var sorted = new TreeMap<Integer, Object>();
                map.forEach((position, entry) -> sorted.put(index(String.valueOf(position)), entry));
                yield List.copyOf(sorted.values());
            }
            default -> throw invalidFields();
        };
        var fields = new ArrayList<ProfileChange.NewField>();
        for (var entry : entries) {
            if (!(entry instanceof Map<?, ?> field)) {
                throw invalidFields();
            }
            fields.add(new ProfileChange.NewField(
                    text(FIELDS + " name", field.get("name")), text(FIELDS + " value", field.get("value"))));
        }
        return fields;
    }

    private static InvalidRequestException invalidFields() {
        return new InvalidRequestException(
                "Validation failed: " + FIELDS + " must be a list of fields, each with a name and a value");
    }

    private static int index(String position) {
        try {
            return Integer.parseInt(position);
        } catch (NumberFormatException e) {
            throw invalidFields();
        }
    }

    /**
     * @return the default quote policy, or null if it isn't given
     */
    private static QuotePolicy source(Object value) {
        if (isEmpty(value)) {
            return null;
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new InvalidRequestException("Validation failed: source must be an object");
        }
        QuotePolicy quotePolicy = null;
        for (var entry : source.entrySet()) {
            var key = String.valueOf(entry.getKey());
            if (key.equals("quote_policy")) {
                if (!isEmpty(entry.getValue())) {
                    quotePolicy = entry.getValue() instanceof String policy ? QuotePolicy.fromValue(policy).orElse(null) : null;
                    if (quotePolicy == null) {
                        throw new InvalidRequestException(
                                "Validation failed: source[quote_policy] must be public, followers, or nobody");
                    }
                }
            } else {
                checkFixed(key, entry.getValue(), FIXED_SOURCE, "source[" + key + "]");
            }
        }
        return quotePolicy;
    }

    /**
     * @param name what to call the setting in an error
     */
    private static void checkFixed(String key, Object value, Map<String, Object> fixed, String name) {
        if (isEmpty(value)) {
            return;
        }
        var expected = fixed.get(key);
        var matches = switch (expected) {
            case null -> false;
            case Boolean flag -> Objects.equals(flag, value instanceof String string ? RequestParameters.flag(string) : value);
            default -> expected.equals(value);
        };
        if (!matches) {
            throw new InvalidRequestException("Validation failed: " + name + " can't be changed yet");
        }
    }

    private static boolean isEmpty(Object value) {
        return value == null || "".equals(value);
    }

    /**
     * A file input left empty is sent with no name and no content.
     */
    private static ProfileChange.Image image(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ProfileChange.Image.KEEP;
        }
        try {
            return ProfileChange.Image.upload(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
