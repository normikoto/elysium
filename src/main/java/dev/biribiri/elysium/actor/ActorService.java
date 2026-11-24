package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.ActorRepository;
import dev.biribiri.elysium.actor.internal.Ed25519Keys;
import dev.biribiri.elysium.actor.internal.InstanceActorKeys;
import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ActorService {

    private final ActorRepository actors;
    private final InstanceActorKeys instanceActorKeys;
    private final InstanceProperties instance;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final ProfileService profiles;

    ActorService(
            ActorRepository actors,
            InstanceActorKeys instanceActorKeys,
            InstanceProperties instance,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events,
            ProfileService profiles) {
        this.events = events;
        this.profiles = profiles;
        this.actors = actors;
        this.instanceActorKeys = instanceActorKeys;
        this.instance = instance;
        this.transaction = new TransactionTemplate(transactionManager);
        // Whatever the database's default is, each statement in onCreated has to see what's been committed so far. Taking
        // a lock and then counting accounts wouldn't otherwise see those committed while waiting for the lock.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Gets the actor for the server itself, creating its key if this is the first time it's been needed. The key is
     * stored straight away, whatever happens to any transaction the caller is in.
     */
    public InstanceActor instanceActor() {
        var keys = instanceActorKeys.get();
        return new InstanceActor(
                instance.resolve(InstanceActor.PATH),
                instance.resolve("/"),
                InstanceActor.USERNAME,
                instance.accountDomain(),
                instance.resolve(InstanceActor.PATH + "/inbox"),
                instance.resolve(InstanceActor.PATH + "/outbox"),
                keys.rsa().publicKeyPem(),
                keys.ed25519().publicKeyPem());
    }

    /**
     * Finds the Ed25519 key that checks a local actor's integrity proofs.
     *
     * @return the key in PEM form, or empty if the actor isn't local, or was deleted long enough ago that its keys are gone
     */
    public Optional<String> findProofPublicKeyPem(Actor actor) {
        return actors.findEd25519PublicKeyPem(actor.id());
    }

    /**
     * Finds a local actor that's neither suspended nor deleted, as the other {@code findLocal} methods also do. Usernames are
     * matched case-insensitively.
     */
    public Optional<Actor> findLocalByUsername(String username) {
        if (!Actor.isValidLocalUsername(username)) {
            return Optional.empty();
        }
        return actors.findLocal(username, instance.accountDomain());
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

    /**
     * Like {@link #findLocalByUri}, for several actors at once, in no particular order. Ones that don't exist are left out.
     */
    public List<Actor> findLocalByUris(Collection<URI> uris) {
        return actors.findLocalByUris(uris.stream().map(URI::toString).distinct().toList());
    }

    /**
     * Finds a local actor in any state, for what has to tell a suspended or deleted account apart from one that never existed,
     * or has to go on working for one. Check the state of what this returns.
     */
    public Optional<Actor> findAnyLocalByUsername(String username) {
        if (!Actor.isValidLocalUsername(username)) {
            return Optional.empty();
        }
        return actors.findAnyLocal(username, instance.accountDomain());
    }

    /**
     * Like {@link #findAnyLocalByUsername}.
     */
    public Optional<Actor> findAnyLocalByUri(URI uri) {
        return actors.findAnyLocalByUri(uri);
    }

    /**
     * Like {@link #findAnyLocalByUsername}.
     */
    public Optional<Actor> findAnyLocalById(UUID id) {
        return actors.findAnyLocalById(id);
    }

    /**
     * Finds a local or remote actor. This can return one that's suspended or deleted, so check its state.
     */
    public Optional<Actor> findByUri(URI uri) {
        return actors.findByUri(uri);
    }

    /**
     * Like {@link #findByUri}, for several actors at once, in no particular order. Ones that don't exist are left out.
     */
    public List<Actor> findByUris(Collection<URI> uris) {
        return actors.findByUris(uris.stream().map(URI::toString).distinct().toList());
    }

    /**
     * Finds a local or remote actor. This can return one that's suspended or deleted, so check its state.
     */
    public Optional<Actor> findById(UUID id) {
        return actors.findById(id);
    }

    /**
     * Like {@link #findById}, for several actors at once, in no particular order. Ones that don't exist are left out.
     */
    public List<Actor> findByIds(Collection<UUID> ids) {
        return actors.findByIds(ids);
    }

    /**
     * @return a remote actor's followers collection, from its document, or empty if it doesn't have one. Local actors' aren't
     * stored, since they're derived from their URIs.
     */
    public Optional<URI> findFollowersUri(URI actor) {
        return actors.findFollowersUri(actor);
    }

    /**
     * @return a remote actor's collection of pinned statuses, from its document, or empty if it doesn't have one. Local
     * actors' aren't stored, since they're derived from their URIs.
     */
    public Optional<URI> findFeaturedUri(UUID actorId) {
        return actors.findFeaturedUri(actorId);
    }

    /**
     * Whether an actor is on this server. Local actors are stored with the account domain, and an actor from another server
     * can't be stored with it, or with this server's authority.
     */
    public boolean isLocal(Actor actor) {
        return actor.domain().equals(instance.accountDomain());
    }

    public boolean isLocal(UUID actorId) {
        return findById(actorId).map(this::isLocal).orElse(false);
    }

    /**
     * Whether this URI is the URI of a suspended or deleted actor. An actor that isn't stored can't be either.
     */
    public boolean isInactive(URI uri) {
        return actors.isInactive(uri);
    }

    /**
     * @return which of these inboxes shouldn't be delivered to, because only suspended actors use them
     */
    public Set<URI> suspendedInboxes(Collection<URI> inboxes) {
        return actors.findSuspendedInboxes(inboxes.stream().map(URI::toString).toList())
                .stream()
                .map(URI::create)
                .collect(Collectors.toSet());
    }

    /**
     * @param prefix the start of their usernames, ignoring case
     * @return a page of the accounts that are neither suspended nor deleted, in the order {@link #list} uses
     */
    public List<Actor> findActiveByUsernamePrefix(String prefix, int limit, int offset) {
        return actors.findActiveByUsernamePrefix(prefix, limit, offset);
    }

    /**
     * Lists accounts, for admins.
     */
    public List<Actor> list(AccountFilter filter) {
        return actors.list(filter);
    }

    /**
     * Suspends a local or remote actor, keeping what's stored about it.
     *
     * @return whether this suspended it, which it won't have if it doesn't exist, was already suspended, or has been deleted
     */
    public boolean suspend(UUID id) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var at = actors.markSuspended(id);
            at.ifPresent(time -> events.publishEvent(new AccountSuspended(id, time)));
            return at.isPresent();
        }));
    }

    /**
     * Starts or stops rejecting a remote actor's attachments, which keeps them from being downloaded. Starting removes the
     * copies already kept, through {@link ActorMediaRejected}.
     *
     * @return whether that changed anything, which it doesn't for a local or deleted actor, or one already set this way
     */
    public boolean setMediaRejected(UUID id, boolean rejected) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var changed = actors.setMediaRejected(id, rejected);
            if (changed) {
                events.publishEvent(rejected ? new ActorMediaRejected(id) : new ActorMediaAllowed(id));
            }
            return changed;
        }));
    }

    public boolean isMediaRejected(UUID id) {
        return !actors.mediaRejectedAmong(List.of(id)).isEmpty();
    }

    /**
     * @return those of these actors whose attachments are rejected
     */
    public Set<UUID> mediaRejectedAmong(Collection<UUID> ids) {
        return actors.mediaRejectedAmong(ids);
    }

    /**
     * Ends an actor's suspension.
     *
     * @return whether this ended it, which it won't have if the actor isn't suspended
     */
    public boolean unsuspend(UUID id) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var at = actors.markUnsuspended(id);
            at.ifPresent(time -> events.publishEvent(new AccountUnsuspended(id, time)));
            return at.isPresent();
        }));
    }

    /**
     * Marks a local actor as permanently deleted. Listeners for {@link AccountDeleted} remove what's stored about it.
     *
     * @return whether this deleted it, which it won't have if it isn't local, or was deleted already
     */
    public boolean markDeleted(UUID id) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var deleted = actors.markDeleted(id);
            if (deleted) {
                events.publishEvent(new AccountDeleted(id));
            }
            return deleted;
        }));
    }

    /**
     * Marks a remote actor as permanently deleted, because its server said so. Its URI stays taken: anything from it is
     * refused from then on, even if its server gives the URI to a new actor. Listeners for {@link AccountDeleted} remove
     * what's stored about it.
     *
     * @return whether this deleted it, which it won't have if it's local, or was deleted already
     */
    public boolean markRemoteDeleted(UUID id) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var deleted = actors.markRemoteDeleted(id);
            if (deleted) {
                events.publishEvent(new AccountDeleted(id));
            }
            return deleted;
        }));
    }

    /**
     * @return the domain of every remote actor, each as {@link Actor#domain} has it
     */
    public List<String> remoteDomains() {
        return actors.findRemoteDomains();
    }

    /**
     * Removes the keys of actors deleted before this time, once nothing can still need to sign as them.
     */
    public void removeKeysOfDeleted(Instant before) {
        actors.removeKeysOfDeleted(before);
    }

    /**
     * Stores an actor from another server, or replaces what's stored about it, which counts as refreshing it, along with its
     * profile. A suspended or deleted actor isn't changed, and neither is its profile.
     *
     * @return the stored actor, which callers have to check is active
     * @throws IllegalArgumentException if the actor claims to be on this server, where it could take the URI that a local
     *                                  account would need, or on the account domain, where it would pass for a local one
     */
    @Transactional
    public Actor upsertRemote(RemoteActorProfile profile) {
        if (instance.isLocalDomain(profile.domain())) {
            throw new IllegalArgumentException("A remote actor can't be on this server's domain");
        }
        var stored = actors.upsertRemote(profile);
        if (stored.isActive()) {
            profiles.replaceRemote(stored.id(), profile.profile());
        }
        return stored;
    }

    /**
     * Finds a stored copy of a remote actor by its username and domain, without asking its server. If several stored actors
     * have had that username, this is the one refreshed most recently. This can return one that's suspended, so check its
     * state.
     *
     * @param domain in lowercase
     */
    public Optional<Actor> findRemoteByAcct(String username, String domain) {
        return actors.findRemoteByAcct(username, domain);
    }

    /**
     * Whether a local account hides who's in its followers and following collections. Their sizes are still shown.
     */
    public boolean hidesCollections(UUID actorId) {
        return !actors.findHidingCollections(List.of(actorId)).isEmpty();
    }

    /**
     * Like {@link #hidesCollections}, for several accounts at once.
     *
     * @return the local accounts among these that hide them
     */
    public Set<UUID> hidingCollections(Collection<UUID> actorIds) {
        return Set.copyOf(actors.findHidingCollections(actorIds));
    }

    /**
     * Sets whether a local account hides who's in its followers and following collections.
     *
     * @return whether the account exists to be changed
     */
    public boolean setHidesCollections(UUID actorId, boolean hide) {
        return actors.setHidesCollections(actorId, hide);
    }

    /**
     * Counts the accounts that the public can see, leaving out suspended and deleted ones.
     */
    public long countLocal() {
        return actors.countLocal();
    }

    /**
     * Counts the accounts that exist, which includes suspended ones, because they can come back.
     */
    public long countExistingLocal() {
        return actors.countExistingLocal();
    }

    /**
     * Counts the accounts created in each week since {@code since}, keyed by the Monday the week starts on, in UTC. Accounts
     * that were deleted since are left out, and suspended ones are counted, since they did sign up.
     */
    public Map<LocalDate, Long> countLocalCreatedByWeek(LocalDate since) {
        return actors.countLocalCreatedByWeek(since);
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
        return createLocalIfAbsent(UUID.randomUUID(), username, onCreated);
    }

    /**
     * Creates a local actor with an ID chosen beforehand, as {@link #createLocalIfAbsent(String, Consumer)} does. A sign-up
     * that waits for its email address to be confirmed chooses its account's ID when it's made, so that a token issued
     * for it then starts working once the account exists.
     */
    public Optional<Actor> createLocalIfAbsent(UUID id, String username, Consumer<Actor> onCreated) {
        if (!Actor.isValidLocalUsername(username)) {
            throw new IllegalArgumentException("Local usernames must be 1 to 30 letters, digits, or underscores");
        }
        // Suspended and deleted accounts keep their usernames.
        if (findAnyLocalByUsername(username).isPresent()) {
            return Optional.empty();
        }

        // Generating keys is slow, so it happens before the transaction takes a database connection.
        var keys = RsaKeys.generate();
        var ed25519 = Ed25519Keys.generate();
        var actorPath = "/users/" + username;
        // PostgreSQL stores microseconds, so this matches what a later read returns.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var actor = new Actor(
                id,
                instance.resolve(actorPath),
                username,
                instance.accountDomain(),
                instance.resolve(actorPath + "/inbox"),
                instance.resolve(actorPath + "/outbox"),
                null,
                keys.publicKeyPem(),
                now,
                now);
        var created = transaction.execute(status -> {
            if (!actors.createLocalIfAbsent(actor, keys.privateKeyPem(), ed25519)) {
                return false;
            }
            onCreated.accept(actor);
            return true;
        });
        return Boolean.TRUE.equals(created) ? Optional.of(actor) : Optional.empty();
    }
}
