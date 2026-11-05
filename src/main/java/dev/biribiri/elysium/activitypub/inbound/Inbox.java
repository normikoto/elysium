package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Decides whether to keep an activity that came with a valid signature. This has to happen before
 * {@link InboundActivities#accept}, where the first activity stored under an ID wins. Otherwise a server could use its own
 * valid key to send an activity with another server's ID, and the real one would be dropped as a repeat.
 */
@Service
public class Inbox {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    private final InboundActivities activities;
    private final JsonMapper json;
    private final DomainBlocks blocks;

    Inbox(InboundActivities activities, JsonMapper json, DomainBlocks blocks) {
        this.activities = activities;
        this.json = json;
        this.blocks = blocks;
    }

    /**
     * @param signer who signed the request
     * @param body   the request's body
     */
    public Outcome receive(VerifiedSignature signer, byte[] body) {
        var parsed = ActivityEnvelope.parse(body, json);
        if (parsed.isEmpty()) {
            return Outcome.INVALID;
        }
        var envelope = parsed.get();
        if (!signer.owner().toString().equals(envelope.actor())) {
            log.debug("Ignoring activity {} from {}, signed by {}", envelope.id(), envelope.actor(), signer.owner());
            return Outcome.IGNORED;
        }
        // The signature check has already refused keys on a blocked server, but a block can have been added since then.
        if (blocks.isBlocked(signer.owner().getHost())) {
            log.debug("Refusing activity {} from {}, whose server is blocked", envelope.id(), signer.owner());
            return Outcome.FORBIDDEN;
        }
        URI id;
        try {
            id = new URI(envelope.id());
        } catch (URISyntaxException e) {
            return Outcome.INVALID;
        }
        if (!isOnActorsServer(id, signer.owner())) {
            log.debug("Refusing activity {} from {}, which is on another server", envelope.id(), signer.owner());
            return Outcome.FORBIDDEN;
        }
        InboundActivity activity;
        try {
            activity = new InboundActivity(envelope.id(), envelope.actor(), envelope.type(), envelope.body());
        } catch (IllegalArgumentException e) {
            return Outcome.INVALID;
        }
        activities.accept(activity);
        return Outcome.ACCEPTED;
    }

    /**
     * @return whether the actor can speak for this ID, because the two are on the same server
     */
    static boolean isOnActorsServer(URI id, URI actor) {
        return "https".equalsIgnoreCase(id.getScheme())
                && id.getHost() != null
                && id.getRawUserInfo() == null
                && HostHeader.value(id.getScheme(), id.getHost(), id.getPort())
                .equals(HostHeader.value(actor.getScheme(), actor.getHost(), actor.getPort()));
    }

    public enum Outcome {
        /** The activity is queued, or it's a repeat of one that was. */
        ACCEPTED,
        /**
         * Someone other than the activity's only actor signed it, which is what forwarding looks like. It can only be
         * trusted by fetching it from its origin, which isn't implemented, so it's dropped. The sender did nothing wrong,
         * and shouldn't try again.
         */
        IGNORED,
        /** The body isn't an activity that can be stored. */
        INVALID,
        /** The activity's ID isn't on its actor's server, so the actor can't speak for it, or that server is blocked. */
        FORBIDDEN
    }
}
