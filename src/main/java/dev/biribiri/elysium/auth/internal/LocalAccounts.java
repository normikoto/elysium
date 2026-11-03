package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * Passwords for local actors.
 */
@Service
class LocalAccounts implements UserDetailsService {

    static final int MIN_PASSWORD_LENGTH = 8;
    static final int MAX_PASSWORD_LENGTH = 128;

    private final ActorService actors;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;

    LocalAccounts(
            ActorService actors, JdbcClient jdbc, PasswordEncoder passwordEncoder, TransactionTemplate transaction) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
    }

    static boolean isValidPassword(String password) {
        return password != null
                && password.length() >= MIN_PASSWORD_LENGTH
                && password.length() <= MAX_PASSWORD_LENGTH;
    }

    /**
     * Loads a user by the username they typed. The returned user's name is the actor ID, because that's what tokens
     * are recorded against. It has to be Spring's own {@link User} class, because Spring only deserializes an
     * allowlist of types when it reads an authorization back from the database.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        return actors.findLocalByUsername(username)
                .flatMap(actor -> passwordHash(actor.id()).map(hash -> User.withUsername(actor.id().toString())
                        .password(hash)
                        .roles("USER")
                        .build()))
                .orElseThrow(() -> new UsernameNotFoundException("No local account with that username"));
    }

    /**
     * Creates a new account.
     *
     * @return whether it was created, which it won't be if the username is taken
     */
    boolean signUp(String username, String password) {
        requireValidPassword(password);
        // Hashing is slow, so it happens before the transaction takes a database connection.
        var hash = passwordEncoder.encode(password);
        return actors.createLocalIfAbsent(username, actor -> insertCredential(actor.id(), hash)).isPresent();
    }

    /**
     * Sets an actor's password, replacing any existing one, and revokes the tokens and pending authorization codes it
     * has issued to applications.
     */
    void resetPassword(UUID actorId, String password) {
        requireValidPassword(password);
        // Hashing is slow, so it happens before the transaction takes a database connection.
        var hash = passwordEncoder.encode(password);
        transaction.executeWithoutResult(status -> {
            jdbc.sql("""
                            INSERT INTO account_credential (actor_id, password_hash)
                            VALUES (:actorId, :hash)
                            ON CONFLICT (actor_id) DO UPDATE
                            SET password_hash = EXCLUDED.password_hash, updated_at = now()
                            """)
                    .param("actorId", actorId)
                    .param("hash", hash)
                    .update();
            jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = :principalName")
                    .param("principalName", actorId.toString())
                    .update();
        });
    }

    private void insertCredential(UUID actorId, String hash) {
        jdbc.sql("""
                        INSERT INTO account_credential (actor_id, password_hash)
                        VALUES (:actorId, :hash)
                        ON CONFLICT DO NOTHING
                        """)
                .param("actorId", actorId)
                .param("hash", hash)
                .update();
    }

    private Optional<String> passwordHash(UUID actorId) {
        return jdbc.sql("SELECT password_hash FROM account_credential WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(String.class)
                .optional();
    }

    private static void requireValidPassword(String password) {
        if (!isValidPassword(password)) {
            throw new IllegalArgumentException("Passwords must be %d to %d characters"
                    .formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
        }
    }
}
