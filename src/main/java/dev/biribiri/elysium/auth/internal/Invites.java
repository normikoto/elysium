package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Links that let someone create an account whatever the registration mode, without confirming an email address or waiting
 * for approval. Only admins make them, with the {@code create-invite} command.
 */
@Service
class Invites {

    private static final String USABLE = """
            code_hash = :codeHash
            AND revoked_at IS NULL
            AND (expires_at IS NULL OR expires_at > now())
            AND (max_uses IS NULL OR uses < max_uses)
            """;

    private final JdbcClient jdbc;
    private final InstanceProperties instance;

    Invites(JdbcClient jdbc, InstanceProperties instance) {
        this.jdbc = jdbc;
        this.instance = instance;
    }

    /**
     * @param maxUses   how many accounts it can create, or null for no limit
     * @param expiresAt when it stops working, or null for never
     */
    Created create(Integer maxUses, Instant expiresAt) {
        if (maxUses != null && maxUses < 1) {
            throw new IllegalArgumentException("An invite has to allow at least one use");
        }
        var id = UUID.randomUUID();
        var code = SecretCodes.generate();
        jdbc.sql("""
                        INSERT INTO invite (id, code_hash, max_uses, expires_at)
                        VALUES (:id, :codeHash, :maxUses, :expiresAt)
                        """)
                .param("id", id)
                .param("codeHash", SecretCodes.hash(code))
                .param("maxUses", maxUses)
                .param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .update();
        return new Created(id, code, url(code));
    }

    List<Invite> list() {
        return jdbc.sql("SELECT * FROM invite ORDER BY created_at DESC")
                .query(Invite.class)
                .list();
    }

    /**
     * @return whether it existed and hadn't been revoked already
     */
    boolean revoke(UUID id) {
        return jdbc.sql("UPDATE invite SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", id)
                .update() > 0;
    }

    /**
     * Whether a code works right now. This is only for turning people away early, since it can change before they finish
     * signing up: {@link #use} is what counts.
     */
    boolean isUsable(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM invite WHERE " + USABLE + ")")
                .param("codeHash", SecretCodes.hash(code))
                .query(Boolean.class)
                .single();
    }

    /**
     * Counts a use of an invite, in the caller's transaction. The update holds the invite's row until the transaction ends,
     * so that two sign-ups can't both take its last use.
     *
     * @throws InviteUnusableException if the code doesn't work
     */
    void use(String code) {
        var used = jdbc.sql("UPDATE invite SET uses = uses + 1 WHERE " + USABLE)
                .param("codeHash", SecretCodes.hash(code))
                .update();
        if (used == 0) {
            throw new InviteUnusableException();
        }
    }

    URI url(String code) {
        return UriComponentsBuilder.fromUri(instance.baseUrl())
                .path(SecurityConfig.SIGNUP_PATH)
                .queryParam("invite", code)
                .build()
                .toUri();
    }

    /**
     * @param code the only copy there is, since only its hash is stored
     */
    record Created(UUID id, String code, URI url) {
    }

    record Invite(UUID id, Integer maxUses, int uses, Instant expiresAt, Instant revokedAt, Instant createdAt) {

        String usesText() {
            return maxUses == null ? uses + (uses == 1 ? " use" : " uses") : uses + " of " + maxUses + " uses";
        }

        Optional<String> unusableBecause(Instant now) {
            if (revokedAt != null) {
                return Optional.of("revoked");
            }
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                return Optional.of("expired");
            }
            if (maxUses != null && uses >= maxUses) {
                return Optional.of("used up");
            }
            return Optional.empty();
        }
    }
}
