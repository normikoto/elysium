package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.moderation.ReportFilter;

import java.util.Locale;
import java.util.Map;
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
        return Ids.parse(value);
    }

    /**
     * Reads a flag the way Mastodon does: a missing or empty value is false, and anything not recognized as false is true.
     */
    static boolean flag(String value) {
        return value != null && !value.isEmpty() && !FALSE_VALUES.contains(value.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads a text value from a request body. An empty value counts as missing, because form clients send fields they
     * haven't filled in. A value of the wrong type is rejected rather than treated as missing.
     */
    static String string(Map<String, ?> parameters, String name) {
        return switch (parameters.get(name)) {
            case null -> null;
            case String string -> string.isEmpty() ? null : string;
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be a string");
        };
    }

    /**
     * Reads a flag from a request body, which can be a boolean or a string read as {@link #flag(String)} does. A missing
     * value is false.
     */
    static boolean flag(Map<String, ?> parameters, String name) {
        return switch (parameters.get(name)) {
            case null -> false;
            case Boolean flag -> flag;
            case String string -> flag(string);
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be a boolean");
        };
    }

    /**
     * Mastodon lets {@code local} win when both it and {@code remote} are given.
     */
    static StatusOrigin origin(String local, String remote) {
        return flag(local) ? StatusOrigin.LOCAL : flag(remote) ? StatusOrigin.REMOTE : StatusOrigin.ANY;
    }

    /**
     * Adds the flag that {@link #origin} read, for the links to a timeline's other pages.
     */
    static void putOrigin(Map<String, ? super String> filters, StatusOrigin origin) {
        switch (origin) {
            case LOCAL -> filters.put("local", "true");
            case REMOTE -> filters.put("remote", "true");
            case ANY -> {
            }
        }
    }

    static IdPage page(String limit, String maxId, String sinceId, String minId) {
        return page(limit, maxId, sinceId, minId, PageLimits.STATUSES, PageLimits.MAX_STATUSES);
    }

    /**
     * For a list of accounts, even one paged by the IDs of something else, such as follows.
     */
    static IdPage accountPage(String limit, String maxId, String sinceId, String minId) {
        return page(limit, maxId, sinceId, minId, PageLimits.ACCOUNTS, PageLimits.MAX_ACCOUNTS);
    }

    static IdPage domainBlockPage(String limit, String maxId, String sinceId, String minId) {
        return page(limit, maxId, sinceId, minId, PageLimits.DOMAIN_BLOCKS, PageLimits.MAX_DOMAIN_BLOCKS);
    }

    /**
     * @param byDefault the limit when none is given
     * @param max       what a larger limit is clamped to, as a smaller one is to 1
     */
    static IdPage page(String limit, String maxId, String sinceId, String minId, int byDefault, int max) {
        return new IdPage(
                Math.clamp(limit(limit, byDefault), 1, max),
                cursor("max_id", maxId),
                cursor("since_id", sinceId),
                cursor("min_id", minId));
    }

    static ReportFilter reportFilter(boolean resolved, String accountId, String targetAccountId) {
        return new ReportFilter(resolved, cursor("account_id", accountId), cursor("target_account_id", targetAccountId));
    }

    /**
     * @param local whether to only list local accounts, if the request also asks for remote ones then both are listed
     */
    static AccountFilter accountFilter(
            boolean local,
            boolean remote,
            boolean suspended,
            String username,
            String domain,
            String limit,
            String maxId,
            String sinceId,
            String minId) {
        return new AccountFilter(
                local == remote ? null : Boolean.valueOf(local),
                suspended,
                username == null || username.isEmpty() ? null : username,
                domain == null || domain.isEmpty() ? null : domain,
                limit(limit, AccountFilter.DEFAULT_LIMIT),
                cursor("max_id", maxId),
                cursor("since_id", sinceId),
                cursor("min_id", minId));
    }

    /**
     * The viewer's account. Public endpoints have no token for anonymous requests, and an app token has no account.
     */
    static Optional<UUID> viewer(TokenPrincipal token) {
        return token == null ? Optional.empty() : token.actorId();
    }

    /**
     * Reads a whole number, or returns the default if it's missing or empty.
     */
    static int number(String name, String value, int defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Validation failed: " + name + " must be a number");
        }
    }

    private static int limit(String value, int defaultLimit) {
        return number("limit", value, defaultLimit);
    }

    private static UUID cursor(String name, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return uuid(value).orElseThrow(() -> new InvalidRequestException("Validation failed: " + name + " is not valid"));
    }
}
