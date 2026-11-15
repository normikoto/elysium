package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.PostFilterChange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads the parameters of Mastodon's filter endpoints, from the maps that {@link JsonBodies#readAny} gives, where they
 * come in Rails' forms: {@code context[]} as an array, and {@code keywords_attributes} as an array or an object of
 * objects in JSON, or as {@code keywords_attributes[][keyword]} or {@code keywords_attributes[0][keyword]} in a form.
 */
final class FilterParameters {

    /**
     * Longer would overflow, and no filter needs to last for longer.
     */
    private static final long MAX_EXPIRES_IN = Duration.ofDays(100 * 365).toSeconds();

    private static final Pattern NESTED_KEYWORD = Pattern.compile("keywords_attributes\\[(\\d*)]\\[(\\w+)]");

    private FilterParameters() {
    }

    /**
     * @return null if there are none, which isn't the same as an empty list
     */
    static Set<PostFilter.Context> contexts(Map<String, ?> parameters) {
        var key = parameters.containsKey("context[]") ? "context[]" : "context";
        if (!parameters.containsKey(key)) return null;
        var contexts = EnumSet.noneOf(PostFilter.Context.class);
        for (var name : list(parameters.get(key), "context")) {
            if (!(name instanceof String string)) {
                throw new InvalidRequestException("Validation failed: context must be a list of strings");
            }
            // A form sends an empty value for a list with nothing chosen.
            if (!string.isEmpty()) contexts.add(PostFilter.Context.fromValue(string));
        }
        return contexts;
    }

    /**
     * @return null if it's left out
     */
    static PostFilter.Action action(Map<String, ?> parameters) {
        var value = RequestParameters.string(parameters, "filter_action");
        return value == null ? null : PostFilter.Action.fromValue(value);
    }

    /**
     * @return null if {@code expires_in} is left out, or no expiry if it's empty
     */
    static PostFilterChange.Expiry expiry(Map<String, ?> parameters) {
        if (!parameters.containsKey("expires_in")) return null;
        long seconds;
        switch (parameters.get("expires_in")) {
            case null -> {
                return new PostFilterChange.Expiry(null);
            }
            case Number number when number.doubleValue() == number.longValue() -> seconds = number.longValue();
            case String string when string.isEmpty() -> {
                return new PostFilterChange.Expiry(null);
            }
            case String string -> {
                try {
                    seconds = Long.parseLong(string.strip());
                } catch (NumberFormatException e) {
                    throw new InvalidRequestException("Validation failed: expires_in must be a number of seconds");
                }
            }
            default -> throw new InvalidRequestException("Validation failed: expires_in must be a number of seconds");
        }
        if (seconds <= 0 || seconds > MAX_EXPIRES_IN) {
            throw new InvalidRequestException("Validation failed: expires_in must be from 1 to " + MAX_EXPIRES_IN);
        }
        return new PostFilterChange.Expiry(Instant.now().plusSeconds(seconds));
    }

    /**
     * @return null if it's left out
     */
    static Boolean flag(Map<String, ?> parameters, String name) {
        return parameters.get(name) == null ? null : RequestParameters.flag(parameters, name);
    }

    /**
     * Form fields that use {@code []} can't be told apart once they're parsed, so the nth value of each field makes the
     * nth keyword. A client that leaves a field out of some keywords but not others has to number them.
     */
    static List<PostFilterChange.KeywordEdit> keywords(Map<String, ?> parameters) {
        var entries = new ArrayList<Map<String, ?>>();
        switch (parameters.get("keywords_attributes")) {
            case null -> {
            }
            case List<?> list -> list.forEach(entry -> entries.add(entry(entry)));
            case Map<?, ?> map -> map.values().forEach(entry -> entries.add(entry(entry)));
            default -> throw invalidKeywords();
        }
        var numbered = new LinkedHashMap<String, Map<String, Object>>();
        var unnumbered = new LinkedHashMap<String, List<?>>();
        for (var parameter : parameters.entrySet()) {
            var matcher = NESTED_KEYWORD.matcher(parameter.getKey());
            if (!matcher.matches()) continue;
            if (matcher.group(1).isEmpty()) {
                unnumbered.put(matcher.group(2), list(parameter.getValue(), "keywords_attributes"));
            } else {
                var values = list(parameter.getValue(), "keywords_attributes");
                numbered.computeIfAbsent(matcher.group(1), index -> new LinkedHashMap<>())
                        .put(matcher.group(2), values.isEmpty() ? null : values.getLast());
            }
        }
        entries.addAll(numbered.values());
        var count = unnumbered.values().stream().mapToInt(List::size).max().orElse(0);
        for (var i = 0; i < count; i++) {
            var entry = new LinkedHashMap<String, Object>();
            for (var field : unnumbered.entrySet()) {
                if (i < field.getValue().size()) entry.put(field.getKey(), field.getValue().get(i));
            }
            entries.add(entry);
        }
        return entries.stream().map(FilterParameters::edit).toList();
    }

    private static PostFilterChange.KeywordEdit edit(Map<String, ?> entry) {
        UUID id = null;
        var idValue = RequestParameters.string(entry, "id");
        if (idValue != null) {
            id = RequestParameters.uuid(idValue).orElseThrow(RecordNotFoundException::new);
        }
        return new PostFilterChange.KeywordEdit(id, RequestParameters.string(entry, "keyword"),
                flag(entry, "whole_word"), RequestParameters.flag(entry, "_destroy"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> entry(Object value) {
        if (value instanceof Map<?, ?> map && map.keySet().stream().allMatch(String.class::isInstance)) {
            return (Map<String, ?>) map;
        }
        throw invalidKeywords();
    }

    private static List<?> list(Object value, String name) {
        return switch (value) {
            case null -> List.of();
            case List<?> list -> list;
            case String string -> List.of(string);
            default -> throw new InvalidRequestException("Validation failed: " + name + " is not valid");
        };
    }

    private static InvalidRequestException invalidKeywords() {
        return new InvalidRequestException("Validation failed: keywords_attributes must be a list of keywords");
    }
}
