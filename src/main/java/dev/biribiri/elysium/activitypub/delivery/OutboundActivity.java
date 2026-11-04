package dev.biribiri.elysium.activitypub.delivery;

import java.util.UUID;

/**
 * An activity to send to other servers.
 *
 * @param id      the activity's {@code id}
 * @param actorId the local actor who signs it
 * @param body    the JSON to send, exactly as it should be signed
 */
public record OutboundActivity(String id, UUID actorId, String body) {
}
