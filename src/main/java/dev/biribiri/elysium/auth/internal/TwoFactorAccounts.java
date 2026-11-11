package dev.biribiri.elysium.auth.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-factor login: each account's TOTP secret, and its recovery codes.
 *
 * <p>Secrets are stored as they are, since the server has to be able to work out the codes, and nothing else in the
 * database is encrypted either. Recovery codes are random enough that only a plain hash of each is stored, as for
 * {@link SecretCodes}.
 */
@Service
class TwoFactorAccounts {

    static final int RECOVERY_CODE_COUNT = 10;

    /**
     * 80 bits, which is 16 characters of Base32.
     */
    private static final int RECOVERY_CODE_BYTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final LocalAccounts accounts;

    TwoFactorAccounts(JdbcClient jdbc, TransactionTemplate transaction, LocalAccounts accounts) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.accounts = accounts;
    }

    boolean isEnabled(UUID actorId) {
        return enabledSecret(actorId).isPresent();
    }

    Optional<byte[]> enabledSecret(UUID actorId) {
        return secret(actorId, true);
    }

    /**
     * @return the secret of a setup that hasn't been confirmed yet
     */
    Optional<byte[]> pendingSecret(UUID actorId) {
        return secret(actorId, false);
    }

    private Optional<byte[]> secret(UUID actorId, boolean enabled) {
        return jdbc.sql("""
                        SELECT totp_secret FROM account_credential
                        WHERE actor_id = :actorId AND totp_secret IS NOT NULL
                          AND (totp_enabled_at IS NOT NULL) = :enabled
                        """)
                .param("actorId", actorId)
                .param("enabled", enabled)
                .query((rs, rowNum) -> rs.getBytes("totp_secret"))
                .optional();
    }

    /**
     * Starts setting up two-factor login, replacing the secret of any earlier setup that wasn't confirmed.
     *
     * @return false if it's already on, or the account has no password
     */
    boolean startSetup(UUID actorId, byte[] secret) {
        return jdbc.sql("""
                        UPDATE account_credential SET totp_secret = :secret, totp_last_step = NULL, updated_at = now()
                        WHERE actor_id = :actorId AND totp_enabled_at IS NULL
                        """)
                .param("actorId", actorId)
                .param("secret", secret)
                .update() > 0;
    }

    /**
     * Turns two-factor login on, with the secret that a code was checked against. Comparing it means that a setup started in
     * another tab meanwhile can't be the one turned on, with a secret that the authenticator app doesn't have.
     *
     * <p>This also logs the account out of every browser, since one of them could be someone else's, who'd otherwise keep their
     * access without a code. The browser that turned it on has to start a new session, with {@link Confirmed#loginGeneration}.
     * Applications keep their tokens, as they do in Mastodon.
     *
     * @param step the time step of the code that was checked, so that it can't also be used to log in
     * @return empty if this isn't the secret being set up
     */
    Optional<Confirmed> confirm(UUID actorId, byte[] secret, long step) {
        return transaction.execute(status -> {
            var confirmed = jdbc.sql("""
                            UPDATE account_credential
                            SET totp_enabled_at = now(), totp_last_step = :step, updated_at = now()
                            WHERE actor_id = :actorId AND totp_enabled_at IS NULL AND totp_secret = :secret
                            """)
                    .param("actorId", actorId)
                    .param("secret", secret)
                    .param("step", step)
                    .update() > 0;
            if (!confirmed) {
                return Optional.<Confirmed>empty();
            }
            var codes = insertRecoveryCodes(actorId);
            return accounts.endSessions(actorId).map(generation -> new Confirmed(codes, generation));
        });
    }

    /**
     * @param recoveryCodes   to show once, since only their hashes are kept
     * @param loginGeneration what a new session for the account belongs to
     */
    record Confirmed(List<String> recoveryCodes, long loginGeneration) {
    }

    /**
     * Uses up a code's time step. Accepting only later steps than the last means that a code that's been seen, by someone
     * looking over a shoulder, say, is no use. Doing it in the update means that two requests with one code can't both
     * succeed.
     *
     * @return whether the step hadn't been used yet
     */
    boolean useStep(UUID actorId, long step) {
        return jdbc.sql("""
                        UPDATE account_credential SET totp_last_step = :step
                        WHERE actor_id = :actorId AND totp_enabled_at IS NOT NULL
                          AND (totp_last_step IS NULL OR totp_last_step < :step)
                        """)
                .param("actorId", actorId)
                .param("step", step)
                .update() > 0;
    }

    /**
     * @return whether this was one of the account's recovery codes, which it no longer is
     */
    boolean useRecoveryCode(UUID actorId, String typed) {
        return jdbc.sql("DELETE FROM account_recovery_code WHERE actor_id = :actorId AND code_hash = :hash")
                .param("actorId", actorId)
                .param("hash", SecretCodes.hash(normalize(typed)))
                .update() > 0;
    }

    int recoveryCodesLeft(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM account_recovery_code WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(Integer.class)
                .single();
    }

    /**
     * @return new recovery codes, which replace the old ones, or empty if two-factor login is off
     */
    Optional<List<String>> replaceRecoveryCodes(UUID actorId) {
        return transaction.execute(status -> {
            // Locks the row, so that turning two-factor login off meanwhile can't leave codes behind.
            var enabled = jdbc.sql("""
                            SELECT 1 FROM account_credential
                            WHERE actor_id = :actorId AND totp_enabled_at IS NOT NULL
                            FOR UPDATE
                            """)
                    .param("actorId", actorId)
                    .query(Integer.class)
                    .optional()
                    .isPresent();
            if (!enabled) {
                return Optional.<List<String>>empty();
            }
            jdbc.sql("DELETE FROM account_recovery_code WHERE actor_id = :actorId").param("actorId", actorId).update();
            return Optional.of(insertRecoveryCodes(actorId));
        });
    }

    /**
     * Turns two-factor login off, or abandons setting it up.
     *
     * @return whether it was on, or being set up
     */
    boolean disable(UUID actorId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var changed = jdbc.sql("""
                            UPDATE account_credential
                            SET totp_secret = NULL, totp_enabled_at = NULL, totp_last_step = NULL, updated_at = now()
                            WHERE actor_id = :actorId AND totp_secret IS NOT NULL
                            """)
                    .param("actorId", actorId)
                    .update() > 0;
            jdbc.sql("DELETE FROM account_recovery_code WHERE actor_id = :actorId").param("actorId", actorId).update();
            return changed;
        }));
    }

    private List<String> insertRecoveryCodes(UUID actorId) {
        var codes = new ArrayList<String>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            var bytes = new byte[RECOVERY_CODE_BYTES];
            RANDOM.nextBytes(bytes);
            var code = Base32.encode(bytes);
            jdbc.sql("INSERT INTO account_recovery_code (actor_id, code_hash) VALUES (:actorId, :hash)")
                    .param("actorId", actorId)
                    .param("hash", SecretCodes.hash(code))
                    .update();
            codes.add(String.join("-", code.substring(0, 4), code.substring(4, 8), code.substring(8, 12),
                    code.substring(12)));
        }
        return codes;
    }

    /**
     * Recovery codes are shown with dashes, in capitals, but people may type them any way.
     */
    private static String normalize(String typed) {
        return typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
}
