package dev.biribiri.elysium.activitypub.inbound;

import java.nio.charset.StandardCharsets;

/**
 * An activity from another server, whose signature has already been checked.
 *
 * @param id       the activity's {@code id}
 * @param actorUri the actor who signed the request
 * @param type     the activity's {@code type}
 * @param body     the activity's JSON
 * @throws IllegalArgumentException if the ID is too long. IDs come from other servers, and they're stored in an index,
 *                                  which has a limit. No real ID comes close to it, so this activity can be refused.
 */
public record InboundActivity(String id, String actorUri, String type, String body) {

    private static final int MAX_ID_BYTES = 2048;

    public InboundActivity {
        if (id.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new IllegalArgumentException("The activity's ID is too long");
        }
    }
}
