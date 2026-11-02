package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives the bootstrap admin its first password. Runs after the admin's actor is created, and never replaces an
 * existing password.
 */
@Component
@Order(1)
class AdminCredentialBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialBootstrap.class);

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final String username;
    private final String password;

    AdminCredentialBootstrap(
            ActorService actors,
            LocalAccounts accounts,
            @Value("${elysium.bootstrap.admin-username:}") String username,
            @Value("${elysium.bootstrap.admin-password:}") String password) {
        this.actors = actors;
        this.accounts = accounts;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank()) {
            return;
        }
        var actor = actors.findLocalByUsername(username);
        if (actor.isEmpty() || accounts.hasPassword(actor.get().id())) {
            return;
        }
        if (password.isEmpty()) {
            log.warn("The admin account {} can't log in: set elysium.bootstrap.admin-password to give it a password",
                    username);
            return;
        }
        if (!LocalAccounts.isValidPassword(password)) {
            throw new IllegalStateException("elysium.bootstrap.admin-password must be %d to %d characters"
                    .formatted(LocalAccounts.MIN_PASSWORD_LENGTH, LocalAccounts.MAX_PASSWORD_LENGTH));
        }
        accounts.setInitialPassword(actor.get().id(), password);
        log.info("Set the password for admin account {}", username);
    }
}
