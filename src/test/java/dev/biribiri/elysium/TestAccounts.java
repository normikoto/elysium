package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * Helps tests deal with the first account being special. Each test class's database can start empty, and the sign-up page
 * refuses to create an account until another one exists.
 */
public final class TestAccounts {

    private TestAccounts() {
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
