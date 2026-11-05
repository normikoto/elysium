package dev.biribiri.elysium.activitypub.delivery;

import java.util.UUID;

/**
 * An activity to send to other servers.
 *
 * @param id             the activity's {@code id}
 * @param actorId        the local actor who signs it
 * @param body           the JSON to send, exactly as it should be signed
 * @param whileSuspended what happens to it if its actor is suspended before it's sent. Only {@link WhileSuspended#SEND} is
 *                       sent once its actor has been deleted.
 */
public record OutboundActivity(String id, UUID actorId, String body, WhileSuspended whileSuspended) {

    public OutboundActivity(String id, UUID actorId, String body) {
        this(id, actorId, body, WhileSuspended.WAIT);
    }

    /**
     * What happens to an activity while its actor is suspended.
     */
    public enum WhileSuspended {
        /**
         * It's dropped, which suits what's only news at the time, such as a new status.
         */
        DROP,
        /**
         * It's sent if the suspension ends, for what would leave other servers out of step if it were lost, such as a status's
         * {@code Delete} or a follow's {@code Accept}. It's dropped if that takes longer than a job can last.
         */
        WAIT,
        /**
         * It's sent anyway, for what says what's happened to the account.
         */
        SEND
    }
}
