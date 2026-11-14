package dev.biribiri.elysium.activitypub.inbound;

/**
 * Processes one type of activity from other servers. An activity of a type with no handler is ignored. A handler that stores
 * something for the local accounts that an activity is for should find them through {@link LocalRecipients}, rather than
 * the inbox it arrived at, which isn't known.
 */
public interface InboundActivityHandler {

    /**
     * The activity's {@code type}, such as {@code Create}. Only one handler can have each type.
     */
    String type();

    /**
     * Processes an activity. No database transaction is open during this. It has to be safe to repeat, because a job can
     * run more than once.
     *
     * @throws dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure if the activity can never be processed. Anything
     *                                                                   else that's thrown means it's tried again later.
     */
    void handle(InboundActivity activity);

    /**
     * Says whether a repeat of an activity from the same actor should be processed again, rather than dropped. This is for
     * activities that can mean something new when they're repeated, because some servers use the same ID each time. Misskey
     * builds the IDs of its {@code Undo}s of follows from the two actors, for example.
     */
    default boolean processesRepeats() {
        return false;
    }

    /**
     * Says whether an activity without an {@code id} is accepted from its own actor, because common servers send this type
     * that way. {@link Inbox} makes it an ID from its body, so a handler that says yes has to process repeats: the same
     * body sent again can be a new change, such as pinning a status again after unpinning it.
     */
    default boolean acceptsWithoutId() {
        return false;
    }
}
