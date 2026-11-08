package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Helps tests deal with the first account being special. Each test class's database can start empty, and the sign-up page
 * refuses to create an account until another one exists.
 */
public final class TestAccounts {

    private TestAccounts() {
    }

    /**
     * An invite that works for any number of accounts, so that a test can sign up with it whatever the registration mode,
     * without confirming an email address. It's added to the database by {@link #invite}.
     */
    private static final String INVITE = "test-invite-for-any-number-of-accounts";

    /**
     * @return the code of {@link #INVITE}, which this makes sure exists
     */
    public static String invite(JdbcClient jdbc) {
        jdbc.sql("""
                        INSERT INTO invite (id, code_hash) VALUES (:id, :codeHash)
                        ON CONFLICT (code_hash) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("codeHash", sha256Hex(INVITE))
                .update();
        return INVITE;
    }

    private static String sha256Hex(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void makeSureAnAccountExists(ActorService actors) {
        if (actors.countLocal() == 0) {
            actors.createLocalIfAbsent("existingaccount", actor -> {
            });
        }
    }

    /**
     * Gives an account the admin role. Do this before it approves an application, since a regular user's approval leaves out
     * admin scopes.
     */
    public static void makeAdmin(JdbcClient jdbc, UUID actorId) {
        jdbc.sql("UPDATE account_credential SET role = 'ADMIN' WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .update();
    }
}
