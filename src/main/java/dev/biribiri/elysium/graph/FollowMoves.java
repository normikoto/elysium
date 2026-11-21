package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.internal.AccountListRepository;
import dev.biribiri.elysium.graph.internal.BlockRepository;
import dev.biribiri.elysium.graph.internal.MuteRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Moves what local accounts have with an account that moved to the account it moved to, as Mastodon does: their follows,
 * with the lists they're in, their blocks, and their mutes. The caller checks that both accounts are active and that
 * neither's server is blocked. Each of these is safe to repeat.
 */
@Service
public class FollowMoves {

    public enum Outcome {
        MOVED,
        /**
         * The follow stays where it is, because the new account can't be followed.
         */
        SKIPPED
    }

    private final FollowService follows;
    private final BlockService blocks;
    private final ActorService actors;
    private final AccountListRepository lists;
    private final BlockRepository blockRows;
    private final MuteRepository mutes;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    FollowMoves(
            FollowService follows,
            BlockService blocks,
            ActorService actors,
            AccountListRepository lists,
            BlockRepository blockRows,
            MuteRepository mutes,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager) {
        this.follows = follows;
        this.blocks = blocks;
        this.actors = actors;
        this.lists = lists;
        this.blockRows = blockRows;
        this.mutes = mutes;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Makes a local account follow {@code to} instead of {@code from}, keeping {@code to} in the lists that had
     * {@code from}, and the follow's options. Following an account on another server, or one that approves its followers by hand, waits for it to
     * accept, while the old follow is undone straight away, as on Mastodon.
     *
     * @param follower a local account that follows {@code from}, or has asked to
     */
    public Outcome moveFollower(UUID moveId, Actor follower, Actor from, Actor to) {
        if (follower.id().equals(to.id())) {
            return Outcome.SKIPPED;
        }
        try {
            transaction.executeWithoutResult(status -> {
                follows.follow(follower, to, follows.options(follower.id(), from.id()).orElse(FollowOptions.NONE));
                // Before the old follow is removed, which takes its list memberships with it.
                lists.copyMembers(follower.id(), from.id(), to.id());
                if (follows.unfollow(follower, from.id())) {
                    events.publishEvent(new FollowMoved(moveId, follower.id(), from.id(), to.id()));
                }
            });
            return Outcome.MOVED;
        } catch (FollowNotAllowedException e) {
            // Thrown from inside the transaction, which can only be rolled back then.
            return Outcome.SKIPPED;
        }
    }

    /**
     * Makes local accounts that block {@code from} block {@code to} as well, which is sent to its server like any other
     * block. One that follows {@code to} chose to, so it's left alone.
     */
    public void carryBlocks(Actor from, Actor to) {
        for (var blockerId : blockRows.findLocalBlockerIds(from.id())) {
            if (blockerId.equals(to.id()) || follows.isFollowing(blockerId, to.id())) {
                continue;
            }
            actors.findLocalById(blockerId).ifPresent(blocker -> blocks.block(blocker, to));
        }
    }

    /**
     * Makes local accounts that mute {@code from} mute {@code to} the same way, unless they follow it.
     */
    public void carryMutes(UUID fromId, UUID toId) {
        transaction.executeWithoutResult(status -> mutes.copyLocalMutes(fromId, toId));
    }

    /**
     * Whether any local account has a block or a mute of the account that a move would carry over.
     */
    public boolean hasLocalBlocksOrMutes(UUID actorId) {
        return blockRows.hasLocalBlockers(actorId) || mutes.hasLocalMuters(actorId);
    }
}
