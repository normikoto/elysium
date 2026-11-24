package dev.biribiri.elysium.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A sign-up that can't be made as entered. It holds what's wrong with each field, in the shape of Mastodon's
 * {@code details}, which apps show next to the fields, and the message is Mastodon's summary of them.
 */
public class InvalidSignUpException extends RuntimeException {

    private final Map<String, List<Problem>> details;

    /**
     * @param details the problems, by the name of the request parameter they're about
     */
    public InvalidSignUpException(Map<String, List<Problem>> details) {
        super("Validation failed: " + details.entrySet().stream()
                .flatMap(field -> field.getValue().stream()
                        .map(problem -> label(field.getKey()) + " " + problem.description()))
                .collect(Collectors.joining(", ")));
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static InvalidSignUpException of(String field, Problem problem) {
        return new InvalidSignUpException(Map.of(field, List.of(problem)));
    }

    public Map<String, List<Problem>> details() {
        return details;
    }

    /**
     * As Rails writes an attribute's name, such as "Invite code" for {@code invite_code}.
     */
    private static String label(String field) {
        var words = field.replace('_', ' ');
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * @param error       one of Mastodon's codes, such as {@code ERR_TAKEN}, which apps can translate
     * @param description Mastodon's English description, such as "has already been taken"
     */
    public record Problem(String error, String description) {

        public static final Problem BLANK = new Problem("ERR_BLANK", "can't be blank");
        public static final Problem INVALID = new Problem("ERR_INVALID", "is invalid");
        public static final Problem TAKEN = new Problem("ERR_TAKEN", "has already been taken");
        public static final Problem NOT_ACCEPTED = new Problem("ERR_ACCEPTED", "must be accepted");

        public static Problem tooShort(int minimum) {
            return new Problem("ERR_TOO_SHORT", "is too short (minimum is %d characters)".formatted(minimum));
        }

        public static Problem tooLong(int maximum) {
            return new Problem("ERR_TOO_LONG", "is too long (maximum is %d characters)".formatted(maximum));
        }
    }
}
