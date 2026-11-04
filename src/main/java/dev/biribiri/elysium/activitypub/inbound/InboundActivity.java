package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.graph.FollowService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * An activity from another server, whose signature has already been checked.
 *
 * @param id              the activity's {@code id}
 * @param actorUri        the actor who signed the request
 * @param type            the activity's {@code type}
 * @param body            the activity's JSON
 * @param firstReceivedAt when it first arrived
 * @param receivedAt      when it last arrived. Jobs have no order, so this is the only way to tell which of two came first.
 * @throws IllegalArgumentException if the ID is too long. IDs come from other servers, and they're stored in an index,
 *                                  which has a limit. No real ID comes close to it, so this activity can be refused.
 */
public record InboundActivity(String id, String actorUri, String type, String body, Instant firstReceivedAt, Instant receivedAt) {

    private static final int MAX_ID_BYTES = 2048;

    public InboundActivity {
        if (!isStorableId(id)) {
            throw new IllegalArgumentException("The activity's ID is too long");
        }
    }

    /**
     * An activity that's arriving now.
     */
    public InboundActivity(String id, String actorUri, String type, String body) {
        // PostgreSQL stores microseconds, so this matches what a later read returns.
        this(id, actorUri, type, body, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * An activity that has only arrived once.
     */
    public InboundActivity(String id, String actorUri, String type, String body, Instant receivedAt) {
        this(id, actorUri, type, body, receivedAt, receivedAt);
    }

    FollowService.Arrival arrival() {
        return new FollowService.Arrival(firstReceivedAt, receivedAt);
    }

    /**
     * @return whether a URI from another server is short enough to store in an index
     */
    static boolean isStorableId(String id) {
        return id.getBytes(StandardCharsets.UTF_8).length <= MAX_ID_BYTES;
    }
}
