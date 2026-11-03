package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Parses request values. Controllers take these as strings, because Spring would otherwise reject a malformed value with
 * a 400 before the authorization check runs.
 */
final class RequestParameters {

    private static final Set<String> FALSE_VALUES = Set.of("0", "f", "false", "off");

    private RequestParameters() {
    }

    /**
     * Parses an ID, or returns empty if it's missing or isn't one.
     */
    static Optional<UUID> uuid(String value) {
        return value == null ? Optional.empty() : Status.parseId(value);
    }

    /**
     * Reads a flag the way Mastodon does: a missing or empty value is false, and anything not recognized as false is true.
     */
    static boolean flag(String value) {
        return value != null && !value.isEmpty() && !FALSE_VALUES.contains(value.toLowerCase(Locale.ROOT));
    }

    static StatusPage page(String limit, String maxId, String sinceId, String minId) {
        return new StatusPage(limit(limit), cursor("max_id", maxId), cursor("since_id", sinceId), cursor("min_id", minId));
    }

    /**
     * The viewer's account. Public endpoints have no token for anonymous requests, and an app token has no account.
     */
    static Optional<UUID> viewer(TokenPrincipal token) {
        return token == null ? Optional.empty() : token.actorId();
    }

    private static int limit(String value) {
        if (value == null || value.isEmpty()) {
            return StatusPage.DEFAULT_LIMIT;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Validation failed: limit must be a number");
        }
    }

    private static UUID cursor(String name, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return uuid(value).orElseThrow(() -> new InvalidRequestException("Validation failed: " + name + " is not valid"));
    }
}
