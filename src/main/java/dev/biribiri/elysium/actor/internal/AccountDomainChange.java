package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * Moves local actors to {@link InstanceProperties#accountDomain} when it's changed, so that an existing server can take
 * one up. Local actors are stored with it because it's what their addresses show, and {@code ActorService.isLocal} goes
 * by it. Their URIs come from the base URL, so they don't change, and other servers pick up the new address when they
 * next refetch an actor and look it up through WebFinger.
 *
 * <p>It runs after migrating, which the server and every CLI command do before anything reads an actor. It uses
 * Flyway's connection because Spring's JDBC beans wait for Flyway.
 */
@Component
class AccountDomainChange implements Callback {

    private static final Logger log = LoggerFactory.getLogger(AccountDomainChange.class);

    private final InstanceProperties instance;

    AccountDomainChange(InstanceProperties instance) {
        this.instance = instance;
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        var domain = instance.accountDomain();
        try {
            var connection = context.getConnection();
            // A stored remote actor on that domain would pass for a local one.
            try (var check = connection.prepareStatement(
                    "SELECT count(*) FROM actor WHERE NOT is_local AND domain = ?")) {
                check.setString(1, domain);
                try (var rows = check.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) {
                        throw new IllegalStateException("elysium.instance.account-domain is " + domain
                                + ", but actors from another server are stored with that domain, and would be taken"
                                + " for local accounts. Another server already uses it, so it can't be this one's.");
                    }
                }
            }
            try (var update = connection.prepareStatement(
                    "UPDATE actor SET domain = ? WHERE is_local AND domain <> ?")) {
                update.setString(1, domain);
                update.setString(2, domain);
                var moved = update.executeUpdate();
                if (moved > 0) {
                    log.info("Moved {} local accounts to the account domain {}", moved, domain);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Couldn't move local accounts to the account domain " + domain, e);
        }
    }

    @Override
    public String getCallbackName() {
        return "account domain";
    }
}
