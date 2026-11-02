package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the actor named by {@code elysium.bootstrap.admin-username}. Runs before anything that expects that actor to
 * exist.
 */
@Component
@Order(0)
public class LocalActorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalActorBootstrap.class);

    private final ActorService actors;
    private final InstanceProperties instance;
    private final String username;

    LocalActorBootstrap(
            ActorService actors,
            InstanceProperties instance,
            @Value("${elysium.bootstrap.admin-username:}") String username) {
        this.actors = actors;
        this.instance = instance;
        this.username = username;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank()) {
            return;
        }
        if (!Actor.isValidLocalUsername(username)) {
            throw new IllegalStateException(
                    "elysium.bootstrap.admin-username must be 1 to 30 letters, digits, or underscores: " + username);
        }
        if (actors.findLocalByUsername(username).isPresent()) {
            return;
        }

        var created = actors.createLocalIfAbsent(username);
        if (created.isPresent()) {
            log.info("Created local actor {}", created.get().uri());
        } else if (actors.findLocalByUsername(username).isEmpty()) {
            // Another instance creating the same actor at the same moment isn't a problem, but this is.
            log.warn("Could not create local actor {}: a non-local actor already has that identity",
                    instance.resolve("/users/" + username));
        }
    }
}
