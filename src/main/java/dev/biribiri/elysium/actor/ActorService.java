package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.InstanceActorKeys;
import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class ActorService {

    private final ActorRepository actors;
    private final InstanceActorKeys instanceActorKeys;
    private final InstanceProperties instance;
    private final TransactionTemplate transaction;

    ActorService(
            ActorRepository actors,
            InstanceActorKeys instanceActorKeys,
            InstanceProperties instance,
            PlatformTransactionManager transactionManager) {
        this.actors = actors;
        this.instanceActorKeys = instanceActorKeys;
        this.instance = instance;
        this.transaction = new TransactionTemplate(transactionManager);
        // Whatever the database's default is, each statement in onCreated has to see what's been committed so far. Taking
        // a lock and then counting accounts wouldn't otherwise see those committed while waiting for the lock.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Gets the actor for the server itself, creating its key if this is the first time it's been needed. Generating a
     * key is slow, so this shouldn't first be called inside a database transaction.
     */
    public InstanceActor instanceActor() {
        return new InstanceActor(
                instance.resolve(InstanceActor.PATH),
                instance.resolve("/"),
                InstanceActor.USERNAME,
                instance.domain(),
                instance.resolve(InstanceActor.PATH + "/inbox"),
                instance.resolve(InstanceActor.PATH + "/outbox"),
                instanceActorKeys.get().publicKeyPem());
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

    public List<Actor> findLocalByIds(Collection<UUID> ids) {
        return actors.findLocalByIds(ids);
    }

    public long countLocal() {
        return actors.countLocal();
    }

    public boolean hasLocalAccounts() {
        return actors.hasLocalAccounts();
    }

    /**
     * Creates a local actor unless an actor with the same identity already exists.
     *
     * @param onCreated runs in the same transaction if the actor is created, so if it fails then the actor isn't created
     *                  either
     * @return the new actor, or empty if the identity is taken
     * @throws IllegalArgumentException if the username isn't valid
     */
    public Optional<Actor> createLocalIfAbsent(String username, Consumer<Actor> onCreated) {
        if (!Actor.isValidLocalUsername(username)) {
            throw new IllegalArgumentException("Local usernames must be 1 to 30 letters, digits, or underscores");
        }
        if (findLocalByUsername(username).isPresent()) {
            return Optional.empty();
        }

        // Generating keys is slow, so it happens before the transaction takes a database connection.
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
        var created = transaction.execute(status -> {
            if (!actors.createLocalIfAbsent(actor, keys.privateKeyPem())) {
                return false;
            }
            onCreated.accept(actor);
            return true;
        });
        return Boolean.TRUE.equals(created) ? Optional.of(actor) : Optional.empty();
    }
}
