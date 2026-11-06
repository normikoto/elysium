package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/**
 * Decides whether to keep an activity that came with a valid signature. This has to happen before
 * {@link InboundActivities#accept}, where the first activity stored under an ID wins. Otherwise a server could use its own
 * valid key to send an activity with another server's ID, and the real one would be dropped as a repeat.
 *
 * <p>An activity that passes the checks counts against its server's limits, through {@link ServerActivityLimits}, whether
 * or not it's a repeat. Requests that fail them don't count, so a server can't use up another's limit.
 */
@Service
public class Inbox {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    /** The largest body an inbox accepts. */
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private final InboundActivities activities;
    private final JsonMapper json;
    private final DomainBlocks blocks;
    private final ActorService actors;
    private final ServerActivityLimits limits;

    Inbox(
            InboundActivities activities,
            JsonMapper json,
            DomainBlocks blocks,
            ActorService actors,
            ServerActivityLimits limits) {
        this.actors = actors;
        this.limits = limits;
        this.activities = activities;
        this.json = json;
        this.blocks = blocks;
    }

    /**
     * @param signer who signed the request
     * @param body   the request's body
     */
    public Result receive(VerifiedSignature signer, byte[] body) {
        var parsed = ActivityEnvelope.parse(body, json);
        if (parsed.isEmpty()) {
            return Result.of(Outcome.INVALID);
        }
        var envelope = parsed.get();
        if (!signer.owner().toString().equals(envelope.actor())) {
            log.debug("Ignoring activity {} from {}, signed by {}", envelope.id(), envelope.actor(), signer.owner());
            return Result.of(Outcome.IGNORED);
        }
        // The signature check has already refused keys on a blocked server, but a block can have been added since then.
        if (blocks.isBlocked(signer.owner().getHost())) {
            log.debug("Refusing activity {} from {}, whose server is blocked", envelope.id(), signer.owner());
            return Result.of(Outcome.FORBIDDEN);
        }
        // Like a block, a suspension can have been added since the signature was checked.
        if (actors.isSuspended(signer.owner())) {
            log.debug("Refusing activity {} from {}, who is suspended", envelope.id(), signer.owner());
            return Result.of(Outcome.FORBIDDEN);
        }
        URI id;
        try {
            id = new URI(envelope.id());
        } catch (URISyntaxException e) {
            return Result.of(Outcome.INVALID);
        }
        if (!isOnActorsServer(id, signer.owner())) {
            log.debug("Refusing activity {} from {}, which is on another server", envelope.id(), signer.owner());
            return Result.of(Outcome.FORBIDDEN);
        }
        InboundActivity activity;
        try {
            activity = new InboundActivity(envelope.id(), envelope.actor(), envelope.type(), envelope.body());
        } catch (IllegalArgumentException e) {
            return Result.of(Outcome.INVALID);
        }
        var admission = limits.tryAdmit(signer.owner(), body.length);
        if (admission instanceof ServerActivityLimits.Admission.Refused(var retryAfter)) {
            log.debug("Refusing activity {} from {}, whose server is over its limit", envelope.id(), signer.owner());
            return new Result(Outcome.RATE_LIMITED, retryAfter);
        }
        try {
            activities.accept(activity);
        } catch (RuntimeException e) {
            // The sender will try again, and shouldn't find its limit used up by attempts that weren't stored.
            ((ServerActivityLimits.Admission.Admitted) admission).release().run();
            throw e;
        }
        return Result.of(Outcome.ACCEPTED);
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

    /**
     * @param retryAfter for {@link Outcome#RATE_LIMITED}, how long to wait before trying again, and otherwise zero
     */
    public record Result(Outcome outcome, Duration retryAfter) {

        static Result of(Outcome outcome) {
            return new Result(outcome, Duration.ZERO);
        }
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
        FORBIDDEN,
        /** The signer's server has delivered too many activities, or too much data, and should try again later. */
        RATE_LIMITED
    }
}
