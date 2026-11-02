package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class ActorService {

    private final ActorRepository actors;
    private final InstanceProperties instance;

    ActorService(ActorRepository actors, InstanceProperties instance) {
        this.actors = actors;
        this.instance = instance;
    }

    /**
     * Finds a local actor. Usernames are matched case-insensitively.
     */
    public Optional<Actor> findLocalByUsername(String username) {
        if (!Actor.isValidLocalUsername(username)) {
            return Optional.empty();
        }
        return actors.findLocal(username, instance.domain());
    }

    public Optional<Actor> findLocalByUri(URI uri) {
        return actors.findLocalByUri(uri);
    }

    public Optional<Actor> findLocalById(UUID id) {
        return actors.findLocalById(id);
    }

    /**
     * Creates a local actor unless an actor with the same identity already exists.
     *
     * @return the new actor, or empty if the identity is taken
     * @throws IllegalArgumentException if the username isn't valid
     */
    public Optional<Actor> createLocalIfAbsent(String username) {
        if (!Actor.isValidLocalUsername(username)) {
            throw new IllegalArgumentException("Local usernames must be 1 to 30 letters, digits, or underscores");
        }
        if (findLocalByUsername(username).isPresent()) {
            return Optional.empty();
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
                keys.publicKeyPem(),
                // PostgreSQL stores microseconds, so this matches what a later read returns.
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        return actors.createLocalIfAbsent(actor, keys.privateKeyPem()) ? Optional.of(actor) : Optional.empty();
    }
}
