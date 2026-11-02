package dev.biribiri.elysium.actor.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates the actor named by {@code elysium.bootstrap.admin-username} until signups exist.
 */
@Component
public class LocalActorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalActorBootstrap.class);

    private final ActorRepository actors;
    private final InstanceProperties instance;
    private final String username;

    LocalActorBootstrap(
            ActorRepository actors,
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
        if (actors.findLocal(username, instance.domain()).isPresent()) {
            return;
        }

        var keys = RsaKeys.generate();
        var actorPath = "/users/" + username;
        var actor = new Actor(
                UUID.randomUUID(),
                instance.resolve(actorPath),
                username,
                instance.domain(),
                instance.resolve(actorPath + "/inbox"),
                instance.resolve(actorPath + "/outbox"),
                null,
                keys.publicKeyPem());
        if (actors.createLocalIfAbsent(actor, keys.privateKeyPem())) {
            log.info("Created local actor {}", actor.uri());
        } else if (actors.findLocal(username, instance.domain()).isEmpty()) {
            // Another instance creating the same actor at the same moment isn't a problem, but this is.
            log.warn("Could not create local actor {}: a non-local actor already has that identity", actor.uri());
        }
    }
}
