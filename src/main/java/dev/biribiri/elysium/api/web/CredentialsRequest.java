package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.content.OtherQuotes;
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
 * as on Mastodon, and for {@code pleroma_background_image}, an empty value instead of a file removes it, as on Pleroma.
 *
 * @param hideCollections null to leave it as it is
 * @param quotePolicy        the default for new statuses, or null to leave it as it is
 * @param quoteOthers        what happens to the quotes of new statuses that aren't approved automatically, or null to
 *                           leave it as it is
 */
record CredentialsRequest(ProfileChange profile, Boolean hideCollections, QuotePolicy quotePolicy,
                          OtherQuotes quoteOthers) {

    private record Source(QuotePolicy quotePolicy, OtherQuotes quoteOthers) {

        static final Source NONE = new Source(null, null);
    }

    /**
     * Settings in {@code source} that don't exist here, with the value that's reported for them.
     */
    private static final Map<String, Object> FIXED_SOURCE = Map.of(
            "privacy", "public",
            "sensitive", false);

    private static final String FIELDS = "fields_attributes";

    private static final String BACKGROUND = "pleroma_background_image";

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
        var source = Source.NONE;
        var removeBackground = false;
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
                case "indexable" -> {
                    if (!isEmpty(value)) {
                        change = change.withIndexable(RequestParameters.flag(values, key));
                    }
                }
                case "discoverable" -> {
                    if (!isEmpty(value)) {
                        change = change.withDiscoverable(RequestParameters.flag(values, key));
                    }
                }
                case "bot" -> {
                    if (!isEmpty(value)) {
                        change = change.withBot(RequestParameters.flag(values, key));
                    }
                }
                case "is_cat" -> {
                    if (!isEmpty(value)) {
                        change = change.withCat(RequestParameters.flag(values, key));
                    }
                }
                case "avatar", "header" -> {
                    if (!isEmpty(value)) {
                        throw notFile(key);
                    }
                }
                case BACKGROUND -> {
                    if (!isEmpty(value)) {
                        throw notFile(key);
                    }
                    // A JSON null leaves it as it is, as on Pleroma, rather than removing it.
                    removeBackground = value != null;
                }
                case "source" -> source = source(value);
                default -> checkFixed(key, value, Map.of(), key);
            }
        }
        change = change.withAvatar(image(files.get("avatar"))).withHeader(image(files.get("header")));
        var background = image(files.get(BACKGROUND));
        change = change.withBackground(
                background == ProfileChange.Image.KEEP && removeBackground ? ProfileChange.Image.REMOVE : background);
        return new CredentialsRequest(change, hide, source.quotePolicy(), source.quoteOthers());
    }

    private static InvalidRequestException notFile(String key) {
        return new InvalidRequestException("Validation failed: " + key + " has to be a file in a multipart form");
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
     * @return the posting defaults it gives
     */
    private static Source source(Object value) {
        if (isEmpty(value)) {
            return Source.NONE;
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new InvalidRequestException("Validation failed: source must be an object");
        }
        QuotePolicy quotePolicy = null;
        OtherQuotes quoteOthers = null;
        for (var entry : source.entrySet()) {
            var key = String.valueOf(entry.getKey());
            if (key.equals("quote_others")) {
                if (!isEmpty(entry.getValue())) {
                    quoteOthers = entry.getValue() instanceof String others ? OtherQuotes.fromValue(others).orElse(null) : null;
                    if (quoteOthers == null) {
                        throw new InvalidRequestException(
                                "Validation failed: source[quote_others] must be reject or review");
                    }
                }
            } else if (key.equals("quote_policy")) {
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
        return new Source(quotePolicy, quoteOthers);
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
