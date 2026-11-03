package dev.biribiri.elysium.auth.internal;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mastodon's OAuth scopes. See <a href="https://docs.joinmastodon.org/api/oauth-scopes/">the scope reference</a>.
 */
public final class OAuthScopes {

    public static final String DEFAULT_SCOPE = "read";

    private static final List<String> READ_SCOPES = List.of(
            "read:accounts", "read:blocks", "read:bookmarks", "read:collections", "read:favourites", "read:filters",
            "read:follows", "read:lists", "read:mutes", "read:notifications", "read:search", "read:statuses");

    private static final List<String> WRITE_SCOPES = List.of(
            "write:accounts", "write:blocks", "write:bookmarks", "write:collections", "write:conversations",
            "write:favourites", "write:filters", "write:follows", "write:lists", "write:media", "write:mutes",
            "write:notifications", "write:reports", "write:statuses");

    private static final List<String> ADMIN_READ_SCOPES = List.of(
            "admin:read:accounts", "admin:read:canonical_email_blocks", "admin:read:domain_allows",
            "admin:read:domain_blocks", "admin:read:email_domain_blocks", "admin:read:ip_blocks",
            "admin:read:reports");

    private static final List<String> ADMIN_WRITE_SCOPES = List.of(
            "admin:write:accounts", "admin:write:canonical_email_blocks", "admin:write:domain_allows",
            "admin:write:domain_blocks", "admin:write:email_domain_blocks", "admin:write:ip_blocks",
            "admin:write:reports");

    /**
     * What each broad scope grants. {@code follow} is deprecated, but existing clients still request it.
     */
    private static final Map<String, List<String>> PARENT_SCOPES = Map.of(
            "read", READ_SCOPES,
            "write", WRITE_SCOPES,
            "follow", List.of("read:blocks", "read:follows", "read:mutes", "write:blocks", "write:follows",
                    "write:mutes"),
            "admin:read", ADMIN_READ_SCOPES,
            "admin:write", ADMIN_WRITE_SCOPES);

    private static final Set<String> SUPPORTED = Stream.of(
                    List.of("profile", "read", "write", "follow", "push", "admin:read", "admin:write"),
                    READ_SCOPES, WRITE_SCOPES, ADMIN_READ_SCOPES, ADMIN_WRITE_SCOPES)
            .flatMap(List::stream)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    private OAuthScopes() {
    }

    public static Set<String> supported() {
        return SUPPORTED;
    }

    /**
     * Parses a space-separated scope list.
     *
     * @return the scopes in the order given, or {@link #DEFAULT_SCOPE} if there are none
     */
    public static Set<String> parse(String scopes) {
        var parsed = scopes == null ? Stream.<String>empty()
                : Arrays.stream(scopes.trim().split("\\s+")).filter(scope -> !scope.isEmpty());
        var result = parsed.collect(Collectors.toCollection(LinkedHashSet::new));
        return result.isEmpty() ? Set.of(DEFAULT_SCOPE) : result;
    }

    /**
     * Adds everything the given scopes grant, for use when checking access.
     */
    public static Set<String> expand(Set<String> scopes) {
        var expanded = new LinkedHashSet<>(scopes);
        for (var scope : scopes) {
            expanded.addAll(PARENT_SCOPES.getOrDefault(scope, List.of()));
        }
        return expanded;
    }

    /**
     * Whether a scope gives access to admin features, so that it only works for admins.
     */
    public static boolean isAdmin(String scope) {
        return scope.startsWith("admin:");
    }

    /**
     * Removes the scopes that only work for admins, keeping the others in their order.
     */
    public static Set<String> withoutAdmin(Set<String> scopes) {
        return scopes.stream()
                .filter(scope -> !isAdmin(scope))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
