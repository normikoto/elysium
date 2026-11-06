package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
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
 * <p>An activity signed by someone other than its actor is never stored as it was sent. {@link ForwardedActivities} fetches
 * it from its origin instead.
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
    private final StoredActivities stored;
    private final ForwardedActivities forwarded;
    private final InstanceProperties instance;

    Inbox(
            InboundActivities activities,
            JsonMapper json,
            DomainBlocks blocks,
            ActorService actors,
            ServerActivityLimits limits,
            StoredActivities stored,
            ForwardedActivities forwarded,
            InstanceProperties instance) {
        this.stored = stored;
        this.forwarded = forwarded;
        this.instance = instance;
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
        if (envelope.actor() == null) {
            log.debug("Ignoring activity {}, which has several actors", envelope.id());
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
        // Before the ID is read, because a forwarder isn't to blame for what's in the activity it passes on.
        if (!signer.owner().toString().equals(envelope.actor())) {
            return forwarded(signer, envelope, body.length);
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
        return admitted(signer, body.length, () -> activities.accept(activity), Outcome.ACCEPTED);
    }

    /**
     * Queues a fetch of an activity that the signer can't speak for. One that couldn't come from its origin is dropped
     * instead, which only depends on what the activity says, since the signer may only be passing it on. The fetches count
     * against the signer's limits, since it's the one causing them.
     */
    private Result forwarded(VerifiedSignature signer, ActivityEnvelope envelope, int bodyBytes) {
        var parsedId = ActivityObject.parseUri(envelope.id());
        var actor = ActivityObject.parseUri(envelope.actor());
        if (parsedId.isEmpty()
                || actor.isEmpty()
                || !isOnActorsServer(parsedId.get(), actor.get())
                || !InboundActivity.isStorableId(envelope.id())
                || RemoteActorDocument.isOnDomain(parsedId.get(), instance.domain())
                || blocks.isBlocked(parsedId.get().getHost())
                || actors.isSuspended(actor.get())) {
            log.debug("Ignoring activity {} from {}, signed by {}, which can't be fetched from its origin",
                    envelope.id(), envelope.actor(), signer.owner());
            return Result.of(Outcome.IGNORED);
        }
        return admitted(signer, bodyBytes, () -> {
            if (!stored.exists(envelope.id())) {
                forwarded.queue(envelope.id());
            }
        }, Outcome.FORWARDED);
    }

    /**
     * Runs the step that keeps the activity, if the signer's server is within its limits.
     */
    private Result admitted(VerifiedSignature signer, int bodyBytes, Runnable keep, Outcome outcome) {
        var admission = limits.tryAdmit(signer.owner(), bodyBytes);
        if (admission instanceof ServerActivityLimits.Admission.Refused(var retryAfter)) {
            log.debug("Refusing an activity from {}, whose server is over its limit", signer.owner());
            return new Result(Outcome.RATE_LIMITED, retryAfter);
        }
        try {
            keep.run();
        } catch (RuntimeException e) {
            // The sender will try again, and shouldn't find its limit used up by attempts that weren't stored.
            ((ServerActivityLimits.Admission.Admitted) admission).release().run();
            throw e;
        }
        return Result.of(outcome);
    }

    /**
     * @param actor can come from another server, so it can be a relative reference or have no host
     * @return whether the actor can speak for this ID, because the two are on the same server
     */
    static boolean isOnActorsServer(URI id, URI actor) {
        return "https".equalsIgnoreCase(id.getScheme())
                && id.getHost() != null
                && id.getRawUserInfo() == null
                && actor.getScheme() != null
                && actor.getHost() != null
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
         * Someone other than the activity's actor signed it, which is what forwarding looks like, so it's queued to be fetched
         * from its origin through {@link ForwardedActivities}. The sender did nothing wrong, whatever the origin says.
         */
        FORWARDED,
        /**
         * The activity has several actors, or it was forwarded and couldn't be fetched from its origin, so it's dropped. The
         * sender did nothing wrong, and shouldn't try again.
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
