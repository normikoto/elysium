package dev.biribiri.elysium.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who a bearer token belongs to.
 *
 * @param applicationId the application that holds the token
 * @param actorId       the user who approved the token, or empty for an app token
 * @param scopes        everything the token grants, including the granular scopes of any broad ones
 * @param issuedAt      when the token was issued
 * @param expiresAt     when the token expires
 */
public record TokenPrincipal(
        String applicationId,
        Optional<UUID> actorId,
        Set<String> scopes,
        Instant issuedAt,
        Instant expiresAt
) implements OAuth2AuthenticatedPrincipal {

    @Override
    public String getName() {
        return actorId.map(UUID::toString).orElse(applicationId);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return Map.of(
                OAuth2TokenIntrospectionClaimNames.IAT, issuedAt,
                OAuth2TokenIntrospectionClaimNames.EXP, expiresAt);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return scopes.stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .collect(Collectors.toUnmodifiableSet());
    }
}
