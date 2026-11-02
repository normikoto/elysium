package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Gives the bootstrap admin its first password. Runs after the admin's actor is created, and never replaces an
 * existing password.
 */
@Component
@Order(1)
class AdminCredentialBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentialBootstrap.class);

    private static final ConfigurationPropertyName PASSWORD_PROPERTY =
            ConfigurationPropertyName.of("elysium.bootstrap.admin-password");

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final String username;
    private final String password;

    @Autowired
    AdminCredentialBootstrap(
            ActorService actors,
            LocalAccounts accounts,
            @Value("${elysium.bootstrap.admin-username:}") String username,
            Environment environment) {
        this(actors, accounts, username, literalPassword(environment));
    }

    AdminCredentialBootstrap(ActorService actors, LocalAccounts accounts, String username, String password) {
        this.actors = actors;
        this.accounts = accounts;
        this.username = username;
        this.password = password;
    }

    /**
     * Reads the password exactly as it was configured. {@code @Value} and {@code @ConfigurationProperties} would both
     * treat a password containing <code>${...}</code> as a placeholder, and {@code @Value} would also evaluate
     * <code>#{...}</code> as an expression.
     */
    static String literalPassword(Environment environment) {
        for (var source : ConfigurationPropertySources.get(environment)) {
            var property = source.getConfigurationProperty(PASSWORD_PROPERTY);
            if (property != null && property.getValue() != null) {
                return property.getValue().toString();
            }
        }
        return "";
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank()) {
            return;
        }
        var actor = actors.findLocalByUsername(username);
        if (actor.isEmpty()) {
            return;
        }
        if (accounts.hasPassword(actor.get().id())) {
            if (!password.isEmpty() && !accounts.passwordMatches(actor.get().id(), password)) {
                log.warn("elysium.bootstrap.admin-password wasn't applied because the account {} already has a "
                        + "different password. If you didn't set it, someone else may have signed up with that "
                        + "username.", username);
            }
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
