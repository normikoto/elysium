package dev.biribiri.elysium.activitypub.delivery;

import java.util.UUID;

/**
 * An activity to send to other servers.
 *
 * @param id           the activity's {@code id}
 * @param actorId      the local actor who signs it
 * @param body         the JSON to send, exactly as it should be signed
 * @param aboutAccount whether it says that its actor has been suspended, unsuspended, or deleted. Only these are sent while
 *                     their actor is suspended or deleted.
 */
public record OutboundActivity(String id, UUID actorId, String body, boolean aboutAccount) {

    public OutboundActivity(String id, UUID actorId, String body) {
        this(id, actorId, body, false);
    }
}
