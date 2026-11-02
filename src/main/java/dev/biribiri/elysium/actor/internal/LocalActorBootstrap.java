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
import java.util.regex.Pattern;

/**
 * Creates the actor named by {@code elysium.bootstrap.admin-username} until signups exist.
 */
@Component
public class LocalActorBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalActorBootstrap.class);
    private static final Pattern VALID_USERNAME = Pattern.compile("[A-Za-z0-9_]+");

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
        if (!VALID_USERNAME.matcher(username).matches()) {
            throw new IllegalStateException(
                    "elysium.bootstrap.admin-username may only contain letters, digits, and underscores: " + username);
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
        }
    }
}
