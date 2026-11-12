package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.moderation.ReportCategory;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads what both report APIs take, so that a client gets the same answer from each.
 */
final class ReportParameters {

    private ReportParameters() {
    }

    /**
     * A form repeats {@code status_ids[]} and {@code rule_ids[]} for each ID, which a single value for each name would lose.
     */
    static Map<String, Object> fromForm(MultiValueMap<String, String> form) {
        var parameters = new HashMap<String, Object>(form.toSingleValueMap());
        for (var name : List.of("status_ids", "rule_ids")) {
            parameters.remove(name + "[]");
            var values = new ArrayList<String>();
            values.addAll(form.getOrDefault(name, List.of()));
            values.addAll(form.getOrDefault(name + "[]", List.of()));
            parameters.put(name, values);
        }
        return parameters;
    }

    /**
     * @param value the category, or null for none
     * @return the category, or empty if none was given
     */
    static Optional<ReportCategory> category(String value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.equals("violation")) {
            throw noRules();
        }
        return Optional.of(ReportCategory.fromValue(value).orElseThrow(() ->
                new InvalidRequestException("Validation failed: category must be spam, legal, or other")));
    }

    /**
     * This server has no rules, so none can be named.
     */
    static void requireNoRules(Map<String, ?> parameters) {
        if (!ids(parameters.get("rule_ids"), "rule_ids").isEmpty()) {
            throw noRules();
        }
    }

    /**
     * @return the IDs in a list, or a single one, leaving out empty ones
     */
    static List<String> ids(Object value, String name) {
        List<?> values = switch (value) {
            case null -> List.of();
            case String string -> List.of(string);
            case List<?> list -> list;
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be a list of IDs");
        };
        var ids = new ArrayList<String>();
        for (var id : values) {
            switch (id) {
                case String string when string.isEmpty() -> {
                }
                case String string -> ids.add(string);
                // Mastodon's rule IDs are numbers, which a JSON body can send as they are.
                case Number number -> ids.add(number.toString());
                default -> throw new InvalidRequestException("Validation failed: " + name + " must be a list of IDs");
            }
        }
        return ids;
    }

    private static InvalidRequestException noRules() {
        return new InvalidRequestException("Validation failed: this server has no rules, so none can be chosen");
    }
}
