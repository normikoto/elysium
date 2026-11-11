package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.internal.BlockRepository;
import dev.biribiri.elysium.graph.internal.FollowRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Blocks of one account by another. A local account's block is sent to a remote target's server, and a remote actor's block
 * of a local account is kept so that it can be honored here. Either way, a block ends the follows between the two, and stops
 * either following the other until it's lifted.
 *
 * <p>Inbound blocks are ordered by when each activity first arrived, as follows are: an {@code Undo} covers its
 * {@code Block}, and any that first arrived before it, and it's remembered for a while in case one of those is handled
 * afterward.
 */
@Service
public class BlockService {

    private static final int REMOVAL_BATCH_SIZE = 1000;

    private final BlockRepository blocks;
    private final FollowRepository followRows;
    private final FollowService follows;
    private final ActorService actors;
    private final ApplicationEventPublisher events;

    BlockService(BlockRepository blocks, FollowRepository followRows, FollowService follows, ActorService actors,
                 ApplicationEventPublisher events) {
        this.blocks = blocks;
        this.followRows = followRows;
        this.follows = follows;
        this.actors = actors;
        this.events = events;
    }

    /**
     * Makes a local account block another, which removes the follows between them, and publishes
     * {@link AccountBlocked}. Blocking an account already blocked does nothing.
     *
     * @param blocker a local account
     */
    @Transactional
    public void block(Actor blocker, Actor target) {
        if (blocker.id().equals(target.id())) {
            throw new IllegalArgumentException("An account can't block itself");
        }
        lockPair(blocker, target);
        if (blocks.exists(blocker.id(), target.id())) {
            return;
        }
        var activityUri = blocker.uri() + "#blocks/" + UUID.randomUUID();
        blocks.insert(blocker.id(), target.id(), activityUri, Instant.now());
        follows.unfollowLocked(blocker.id(), target.id());
        follows.removeFollowerLocked(target, blocker.id());
        events.publishEvent(new AccountBlocked(blocker.id(), target.id(), activityUri));
    }

    /**
     * Lifts a local account's block, whatever state the other account is in, and publishes {@link AccountUnblocked}.
     *
     * @return whether there was a block
     */
    @Transactional
    public boolean unblock(Actor blocker, UUID targetId) {
        var target = actors.findById(targetId);
        if (target.isEmpty()) {
            return false;
        }
        lockPair(blocker, target.get());
        var removed = blocks.deletePair(blocker.id(), targetId);
        removed.ifPresent(row -> events.publishEvent(new AccountUnblocked(blocker.id(), targetId, row.activityUri())));
        return removed.isPresent();
    }

    /**
     * Whether a local account's block still has this activity behind it, so that a {@code Block} queued for an earlier one
     * isn't sent.
     */
    public boolean isCurrent(UUID blockerId, UUID blockedId, String activityUri) {
        return blocks.existsThrough(blockerId, blockedId, activityUri);
    }

    /**
     * @return those of the other accounts that the account blocks, or that block it
     */
    public Set<UUID> inBlockWith(UUID accountId, Collection<UUID> otherIds) {
        var found = new HashSet<UUID>();
        for (var block : blocks.findPairsWith(accountId, otherIds)) {
            found.add(block.accountId().equals(accountId) ? block.targetId() : block.accountId());
        }
        return found;
    }

    /**
     * @return those of the accounts that block the viewer, or that have blocked the viewer's server, so that the viewer
     * can't see what they post
     */
    public Set<UUID> refusing(UUID viewerId, Collection<UUID> accountIds) {
        return blocks.refusing(viewerId, accountIds);
    }

    /**
     * @return those of the accounts that block the sender, or that have blocked the sender's server, so that what it sends
     * isn't for them
     */
    public Set<UUID> refusingSender(Collection<UUID> accountIds, URI senderUri) {
        return blocks.refusingSender(accountIds, senderUri);
    }

    /**
     * Whether someone here would take what the sender sends about an actor: the actor itself, if it's local, or else one
     * of its local followers. A local actor's block isn't overruled by its followers, since what's sent concerns it.
     *
     * @param senderUri has to be stored, or this is {@code false}
     */
    public boolean isAcceptedThrough(URI actorUri, URI senderUri) {
        return blocks.isAcceptedThrough(actorUri, senderUri);
    }

    /**
     * @return those of the accounts whose posts a local viewer shouldn't see in threads: those either blocks, those the
     * viewer has muted, and those on servers the viewer has blocked
     */
    public Set<UUID> hiddenFrom(UUID viewerId, Collection<UUID> accountIds) {
        return blocks.hiddenAmong(viewerId, accountIds);
    }

    /**
     * Lists the accounts a local account blocks, newest first, leaving out suspended and deleted ones.
     */
    public List<FollowEntry> blocked(UUID blockerId, FollowPage page) {
        return blocks.findPage(blockerId, page);
    }

    /**
     * Whether an inbound {@code Block} has been undone. {@link #receive} checks too, so this is only for avoiding
     * unnecessary work.
     */
    public boolean wasUndone(String activityUri, String blockerUri, UUID targetId, Instant firstArrival) {
        return blocks.isUndone(activityUri, blockerUri) || blocks.isPairUndone(blockerUri, targetId, firstArrival);
    }

    /**
     * Stores a remote actor's block of a local account, unless it's been undone, or the block already comes from an activity
     * that first arrived later. The actor's follow of the account is removed without telling its server, which knows, and
     * the account's follow of the actor is withdrawn, as Mastodon does.
     *
     * @param target a local account
     * @return the actor's follow of the account that was removed, whose {@code Accept} shouldn't be sent if it's waiting
     */
    @Transactional
    public Optional<FollowService.Removed> receive(Actor blocker, Actor target, String activityUri, Instant firstArrival) {
        var blockerUri = blocker.uri().toString();
        lockPair(blocker, target);
        followRows.lock(activityUri);
        if (wasUndone(activityUri, blockerUri, target.id(), firstArrival)) {
            return Optional.empty();
        }
        var existing = blocks.findByPair(blocker.id(), target.id());
        if (existing.isPresent()) {
            if (!existing.get().activityAt().isAfter(firstArrival)) {
                blocks.replaceActivity(existing.get().id(), activityUri, firstArrival);
            }
            return Optional.empty();
        }
        blocks.insert(blocker.id(), target.id(), activityUri, firstArrival);
        var removed = follows.coverLocked(blockerUri, target.id(), firstArrival);
        follows.unfollowLocked(target.id(), blocker.id());
        events.publishEvent(new AccountBlocked(blocker.id(), target.id(), activityUri));
        return removed;
    }

    /**
     * Applies an {@code Undo} of a remote actor's block. A known activity covers older blocks of the same account, as for
     * follows. An unknown one with a target covers those that arrived up to the {@code Undo}, and an ID alone covers only
     * itself.
     *
     * @param activityUri  the {@code Block}'s ID, or null if the {@code Undo} only names the two actors
     * @param target       the local account it blocked, if that's known
     * @param firstArrival when the {@code Block} first arrived, if that's known
     * @param undoAt       when the {@code Undo} arrived
     */
    @Transactional
    public void undo(String actorUri, String activityUri, Optional<Actor> target, Optional<Instant> firstArrival,
                     Instant undoAt) {
        var blocker = actors.findByUri(URI.create(actorUri));
        target.ifPresent(found -> blocker.ifPresentOrElse(
                actor -> lockPair(actor, found),
                () -> followRows.lock(blockLockKey(actorUri, found.uri().toString()))));
        if (activityUri != null) {
            followRows.lock(activityUri);
            blocks.markUndone(activityUri, actorUri, undoAt);
        }
        var stored = activityUri == null ? Optional.<BlockRepository.Row>empty()
                : blocker.flatMap(actor -> blocks.findByActivity(actor.id(), activityUri));
        var arrival = firstArrival.or(() -> stored.map(BlockRepository.Row::activityAt));
        var targetId = target.map(Actor::id).or(() -> stored.map(BlockRepository.Row::targetId));
        if (targetId.isPresent() && (arrival.isEmpty() || !arrival.get().isAfter(undoAt))) {
            var upTo = arrival.orElse(undoAt);
            blocks.markPairUndone(actorUri, targetId.get(), upTo);
            blocker.ifPresent(actor -> blocks.deleteByPair(actor.id(), targetId.get(), upTo));
        } else if (activityUri != null) {
            blocker.ifPresent(actor -> blocks.deleteByActivity(actor.id(), activityUri));
        }
    }

    /**
     * Removes the blocks an actor made and those of it.
     */
    public void removeForActor(UUID actorId) {
        blocks.deleteForActor(actorId);
    }

    /**
     * Removes the blocks that a domain's remote actors made of local accounts, in batches that commit by themselves. Local
     * accounts' blocks of those actors are kept, since a domain block can be lifted.
     *
     * @param domain a domain from {@link FollowService#remoteDomains}
     */
    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> actorIds;
        do {
            actorIds = followRows.findRemoteActorIds(domain, after, REMOVAL_BATCH_SIZE);
            if (!actorIds.isEmpty()) {
                blocks.deleteMadeBy(actorIds);
                after = actorIds.getLast();
            }
        } while (actorIds.size() == REMOVAL_BATCH_SIZE);
    }

    /**
     * Locks a pair of accounts for a block in either direction, then the follows each way, so that blocks and follows of the
     * same two accounts are made one at a time. Each lock is taken in an order that doesn't depend on who's blocking, so two
     * accounts blocking each other at once can't deadlock.
     */
    private void lockPair(Actor one, Actor other) {
        followRows.lock(blockLockKey(one.uri().toString(), other.uri().toString()));
        var oneWay = FollowService.pairKey(one.uri().toString(), other.id());
        var otherWay = FollowService.pairKey(other.uri().toString(), one.id());
        followRows.lock(oneWay.compareTo(otherWay) < 0 ? oneWay : otherWay);
        followRows.lock(oneWay.compareTo(otherWay) < 0 ? otherWay : oneWay);
    }

    private static String blockLockKey(String oneUri, String otherUri) {
        return oneUri.compareTo(otherUri) < 0 ? "blocks\n" + oneUri + "\n" + otherUri : "blocks\n" + otherUri + "\n" + oneUri;
    }
}
