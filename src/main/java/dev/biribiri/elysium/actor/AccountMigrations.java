package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.actor.internal.AccountMoveRepository;
import dev.biribiri.elysium.actor.internal.ProfileRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Moving accounts, as FEP-7628 and Mastodon do. An account lists the accounts that are also it, its aliases, in
 * {@code alsoKnownAs}, and one of those can then move to it, which sets the old one's {@code movedTo}. Followers of the old
 * account are moved through {@link AccountMoved}, whichever servers the two accounts are on.
 *
 * <p>A local account that moves keeps working: it's only marked as moved, and can stop being, though the followers that
 * were moved stay moved.
 */
@Service
public class AccountMigrations {

    public static final int MAX_ALIASES = 10;

    /**
     * How long a local account has to wait between moves, as on Mastodon.
     */
    public static final Duration LOCAL_COOLDOWN = Duration.ofDays(30);

    /**
     * How long a {@code Move} from an account on another server is ignored after the last one was acted on, as on Mastodon,
     * so that its server can't keep its followers here following and unfollowing.
     */
    public static final Duration REMOTE_COOLDOWN = Duration.ofDays(7);

    private static final int REMOVAL_BATCH = 500;

    private final ActorService actors;
    private final ProfileService profileService;
    private final ProfileRepository profiles;
    private final AccountMoveRepository moves;
    private final ApplicationEventPublisher events;

    AccountMigrations(
            ActorService actors,
            ProfileService profileService,
            ProfileRepository profiles,
            AccountMoveRepository moves,
            ApplicationEventPublisher events) {
        this.actors = actors;
        this.profileService = profileService;
        this.profiles = profiles;
        this.moves = moves;
        this.events = events;
    }

    /**
     * Lets a local account move here from {@code alias}. Its followers' servers are told through {@link ProfileUpdated}.
     *
     * @throws InvalidMigrationException if it's this account, already an alias, or one too many
     */
    @Transactional
    public void addAlias(UUID actorId, Actor alias) {
        var actor = actors.findLocalById(actorId).orElseThrow(() -> new InvalidMigrationException("Account not found"));
        if (alias.uri().equals(actor.uri())) {
            throw new InvalidMigrationException("An account can't be an alias of itself");
        }
        if (!alias.isActive()) {
            throw new InvalidMigrationException("That account is suspended or deleted");
        }
        var old = profiles.lock(actorId);
        if (old.alsoKnownAs().contains(alias.uri())) {
            throw new InvalidMigrationException("That account is already an alias");
        }
        if (old.alsoKnownAs().size() >= MAX_ALIASES) {
            throw new InvalidMigrationException("An account can't have more than " + MAX_ALIASES + " aliases");
        }
        var aliases = new ArrayList<>(old.alsoKnownAs());
        aliases.add(alias.uri());
        events.publishEvent(new ProfileUpdated(actorId, profiles.saveAliases(actorId, aliases)));
    }

    /**
     * @return false if it wasn't an alias, or there's no active local account
     */
    @Transactional
    public boolean removeAlias(UUID actorId, URI alias) {
        if (actors.findLocalById(actorId).isEmpty()) {
            return false;
        }
        var old = profiles.lock(actorId);
        if (!old.alsoKnownAs().contains(alias)) {
            return false;
        }
        var aliases = old.alsoKnownAs().stream().filter(uri -> !uri.equals(alias)).toList();
        events.publishEvent(new ProfileUpdated(actorId, profiles.saveAliases(actorId, aliases)));
        return true;
    }

    /**
     * Moves a local account to another, which has to list it as an alias. The caller fetches an account on another server
     * again just before this, since that's only listed in what its actor document says.
     *
     * @return the move
     * @throws InvalidMigrationException if the move isn't allowed, and then nothing is changed
     */
    @Transactional
    public UUID moveLocal(UUID actorId, Actor target) {
        var actor = actors.findLocalById(actorId).orElseThrow(() -> new InvalidMigrationException("Account not found"));
        if (target.id().equals(actorId)) {
            throw new InvalidMigrationException("An account can't move to itself");
        }
        if (!target.isActive()) {
            throw new InvalidMigrationException("That account is suspended or deleted");
        }
        var old = profiles.lock(actorId);
        var targetProfile = profileService.find(target.id());
        if (!targetProfile.alsoKnownAs().contains(actor.uri())) {
            throw new InvalidMigrationException("That account doesn't list this one as an alias yet");
        }
        if (targetProfile.movedTo() != null) {
            throw new InvalidMigrationException("That account has moved too");
        }
        if (target.uri().equals(old.movedTo())) {
            throw new InvalidMigrationException("This account has already moved there");
        }
        moves.latestWithin(actorId, LOCAL_COOLDOWN).ifPresent(last -> {
            throw new InvalidMigrationException("An account can only move once every " + LOCAL_COOLDOWN.toDays()
                    + " days. This one can move again after " + last.plus(LOCAL_COOLDOWN));
        });
        var at = profiles.saveMovedTo(actorId, target.uri());
        var id = Ids.timeOrdered(at);
        moves.insert(id, actorId, target.id(), null);
        // The Update this sends carries the profile change too, so ProfileUpdated isn't published as well.
        events.publishEvent(new AccountMoved(id, actorId, target.id(), at));
        return id;
    }

    /**
     * Stops a local account being marked as moved. The followers that were moved stay with the account they were moved to.
     *
     * @return false if it wasn't moved, or there's no active local account
     */
    @Transactional
    public boolean undoLocalMove(UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) {
            return false;
        }
        var old = profiles.lock(actorId);
        if (old.movedTo() == null) {
            return false;
        }
        events.publishEvent(new ProfileUpdated(actorId, profiles.saveMovedTo(actorId, null)));
        return true;
    }

    /**
     * Acts on a {@code Move} from an account on another server, whose target the caller has checked lists it as an alias.
     * Both accounts have to be active, and the caller checks that neither's server is blocked, in the same transaction.
     *
     * @param activityUri the {@code Move}'s ID, so that receiving it again does nothing
     * @return the move, or empty if this activity was already acted on, or the account moved too recently
     */
    @Transactional
    public Optional<UUID> recordRemoteMove(UUID actorId, UUID targetId, String activityUri) {
        var actor = actors.findById(actorId).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        var target = actors.findById(targetId).filter(Actor::isActive);
        if (actor.isEmpty() || target.isEmpty() || actorId.equals(targetId)) {
            return Optional.empty();
        }
        // Holds off another Move from the same account until this one commits, so that only one is acted on.
        profiles.lock(actorId);
        if (moves.existsForActivity(activityUri) || moves.latestWithin(actorId, REMOTE_COOLDOWN).isPresent()) {
            return Optional.empty();
        }
        var id = Ids.timeOrdered(Instant.now());
        var at = moves.insert(id, actorId, targetId, activityUri);
        if (at.isEmpty()) {
            return Optional.empty();
        }
        // Its actor document may not say so yet, since servers cache them, and it's fetched again later.
        profiles.markRemoteMoved(actorId, target.get().uri());
        events.publishEvent(new AccountMoved(id, actorId, targetId, at.get()));
        return Optional.of(id);
    }

    /**
     * @return whether a {@code Move} from this account would be ignored, because one was acted on too recently
     */
    public boolean recentlyMoved(UUID actorId) {
        return moves.latestWithin(actorId, REMOTE_COOLDOWN).isPresent();
    }

    /**
     * @return when a local account can next move, if it has to wait
     */
    public Optional<Instant> nextLocalMoveAt(UUID actorId) {
        return moves.latestWithin(actorId, LOCAL_COOLDOWN).map(last -> last.plus(LOCAL_COOLDOWN));
    }

    public Optional<AccountMove> find(UUID moveId) {
        return moves.find(moveId).map(row -> new AccountMove(row.id(), row.actorId(), row.targetId(), row.createdAt()));
    }

    /**
     * Whether a local account is still marked as moved to this account, which it can have stopped being since it moved.
     */
    public boolean isMovedTo(UUID actorId, URI target) {
        return Objects.equals(profileService.find(actorId).movedTo(), target);
    }

    /**
     * Removes the moves an account made, for when it's deleted. Moves to it stay, so that whoever moved there still has to
     * wait before moving again. Safe to repeat.
     */
    public void removeForActor(UUID actorId) {
        moves.deleteForActor(actorId);
    }

    /**
     * Removes the moves made by a server's accounts, for when it's blocked. Moves to them stay, as for
     * {@link #removeForActor}. Safe to stop partway, and to repeat.
     *
     * @param domain as actors have it
     */
    public void removeForDomain(String domain) {
        while (moves.deleteBatchForDomain(domain, REMOVAL_BATCH) > 0) {
            // Each batch commits by itself.
        }
    }

    /**
     * @return the accounts that a local account lists as also being it
     */
    public List<URI> aliases(UUID actorId) {
        return profileService.find(actorId).alsoKnownAs();
    }
}
