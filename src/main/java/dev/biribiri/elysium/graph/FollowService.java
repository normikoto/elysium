package dev.biribiri.elysium.graph;

import dev.biribiri.elysium.graph.internal.FollowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Who follows whom. A follow can be identified by the activity that created it, which other servers refer to when they undo
 * it. An undone activity is remembered for a while, because jobs have no order, and the activity's job can run afterward.
 */
@Service
public class FollowService {

    private final FollowRepository follows;

    FollowService(FollowRepository follows) {
        this.follows = follows;
    }

    /**
     * Stores an accepted follow, or replaces the activity of one that already exists, unless that activity has been undone.
     * This takes part in the caller's transaction, so that the caller can save its answer along with it.
     *
     * @return whether the follow was stored
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean accept(UUID followerId, UUID followedId, String activityUri) {
        // The caller checks wasUndone before fetching anything, but an Undo can run after that, or be running right now.
        // Without the lock, neither transaction would see what the other one is doing.
        follows.lockActivity(activityUri);
        if (follows.isUndone(activityUri)) {
            return false;
        }
        follows.saveAccepted(followerId, followedId, activityUri);
        return true;
    }

    /**
     * Whether an activity has been undone. {@link #accept} checks too, so this is only for avoiding unnecessary work.
     */
    public boolean wasUndone(String activityUri) {
        return follows.isUndone(activityUri);
    }

    /**
     * Removes the follow that an activity created, if the follower has one, and stops the activity from creating one later.
     * A follow for the same two actors that came from a different activity is left alone.
     */
    @Transactional
    public void undo(UUID followerId, String activityUri) {
        follows.lockActivity(activityUri);
        follows.deleteByActivity(followerId, activityUri);
        follows.markUndone(activityUri);
    }

    /**
     * Stops an activity from creating a follow later, for when its actor isn't known yet.
     */
    @Transactional
    public void undo(String activityUri) {
        follows.lockActivity(activityUri);
        follows.markUndone(activityUri);
    }

    /**
     * Lets an undone activity create a follow again, for when it's been sent again. This is for arrivals only: a job for the
     * activity running again doesn't count, and mustn't call this.
     */
    @Transactional
    public void forgetUndone(String activityUri) {
        follows.lockActivity(activityUri);
        follows.unmarkUndone(activityUri);
    }

    /**
     * Removes a follow, for when an {@code Undo} doesn't say which activity created it.
     */
    public void removeByPair(UUID followerId, UUID followedId) {
        follows.deleteByPair(followerId, followedId);
    }
}
