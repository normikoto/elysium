package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;

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
}
