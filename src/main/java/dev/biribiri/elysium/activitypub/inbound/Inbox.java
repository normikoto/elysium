package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.http.HostHeader;
import dev.biribiri.elysium.activitypub.proof.ActivityProofs;
import dev.biribiri.elysium.activitypub.signature.Digests;
import dev.biribiri.elysium.activitypub.signature.KeyFetchBudget;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides whether to keep an activity that came with a valid signature. This has to happen before
 * {@link InboundActivities#accept}, where the first activity stored under an ID wins. Otherwise a server could use its own
 * valid key to send an activity with another server's ID, and the real one would be dropped as a repeat.
 *
 * <p>An activity signed by someone other than its actor is only stored as it was sent if it has an integrity proof
 * (FEP-8b32) from its actor, which says the actor wrote it whoever passed it on. Otherwise {@link ForwardedActivities}
 * fetches it from its origin instead.
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
    private final DeletionChecks deletions;
    private final InstanceProperties instance;
    private final ActivityProofs proofs;

    Inbox(
            InboundActivities activities,
            JsonMapper json,
            DomainBlocks blocks,
            ActorService actors,
            ServerActivityLimits limits,
            StoredActivities stored,
            ForwardedActivities forwarded,
            DeletionChecks deletions,
            InstanceProperties instance,
            ActivityProofs proofs) {
        this.proofs = proofs;
        this.deletions = deletions;
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
     * Blocks while fetching the key of a forwarded activity's actor, so this mustn't be called inside a database
     * transaction.
     *
     * @param signer who signed the request
     * @param body   the request's body
     * @param budget the key fetches that the request's sender is allowed to cause, for checking a forwarded activity's
     *               integrity proof
     */
    public Result receive(VerifiedSignature signer, byte[] body, KeyFetchBudget budget) {
        var parsed = ActivityEnvelope.parse(body, json);
        if (parsed.isEmpty()) {
            return Result.of(Outcome.INVALID);
        }
        var envelope = parsed.get();
        if (envelope.id() == null && !activities.acceptsWithoutId(envelope.type())) {
            return Result.of(Outcome.INVALID);
        }
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
        if (actors.isInactive(signer.owner())) {
            log.debug("Refusing activity {} from {}, who is suspended or deleted", envelope.id(), signer.owner());
            return Result.of(Outcome.FORBIDDEN);
        }
        URI speaker = signer.owner();
        if (!signer.owner().toString().equals(envelope.actor())) {
            var actor = ActivityObject.parseUri(envelope.actor());
            // A server that's over its limit is refused either way, so its proofs aren't worth checking.
            if (actor.isEmpty()
                    || !limits.hasRoom(signer.owner())
                    || !proofs.isSignedBy((ObjectNode) json.readTree(envelope.body()), actor.get(), budget)) {
                // Before the ID is read, because a forwarder isn't to blame for what's in the activity it passes on.
                return forwarded(signer, envelope, body.length);
            }
            speaker = actor.get();
        }
        // Whatever's wrong with an activity that was passed on is its actor's doing, not the forwarder's.
        var refused = speaker.equals(signer.owner()) ? Outcome.FORBIDDEN : Outcome.IGNORED;
        var invalid = speaker.equals(signer.owner()) ? Outcome.INVALID : Outcome.IGNORED;
        var activityId = envelope.id() == null ? anonymousId(envelope) : envelope.id();
        URI id;
        try {
            id = new URI(activityId);
        } catch (URISyntaxException e) {
            return Result.of(invalid);
        }
        if (!isOnActorsServer(id, speaker)) {
            log.debug("Refusing activity {} from {}, which is on another server", envelope.id(), speaker);
            return Result.of(refused);
        }
        InboundActivity activity;
        try {
            activity = new InboundActivity(activityId, envelope.actor(), envelope.type(), envelope.body());
        } catch (IllegalArgumentException e) {
            return Result.of(invalid);
        }
        // The signer's server, which sent it, rather than the actor's, so that a forwarder can't use up another's limits.
        return admitted(signer.owner(), body.length, () -> activities.accept(activity), Outcome.ACCEPTED);
    }

    /**
     * Takes an actor's {@code Delete} of itself whose signature wasn't accepted: because its key went with it, as on
     * Mastodon, or because it's suspended here, which would otherwise keep its statuses stored for good. Since anyone could
     * send this, nothing in it is trusted: it only queues a check that the actor's own server says the actor is gone. That's
     * only for actors stored here and not already deleted, so the check can't be aimed at any other URL.
     *
     * @param actor the actor that the signature's key was taken to be, which has to be the {@code Delete}'s
     * @return the result, or empty if this isn't such a {@code Delete}, and the signature should be refused
     */
    public Optional<Result> receiveUnverifiedDelete(URI actor, byte[] body) {
        var envelope = ActivityEnvelope.parse(body, json);
        if (envelope.isEmpty()
                || envelope.get().id() == null
                || !"Delete".equals(envelope.get().type())
                || !actor.toString().equals(envelope.get().actor())
                || !DeleteHandler.objectOf(envelope.get().body(), json).equals(Optional.of(actor))
                || blocks.isBlocked(actor.getHost())) {
            return Optional.empty();
        }
        var stored = actors.findByUri(actor).filter(found -> !found.isDeleted() && !actors.isLocal(found));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(admitted(actor, body.length, () -> deletions.queue(actor, actor), Outcome.ACCEPTED));
    }

    /**
     * Queues a fetch of an activity that the signer can't speak for. One that couldn't come from its origin is dropped
     * instead, which only depends on what the activity says, since the signer may only be passing it on. The fetches count
     * against the signer's limits, since it's the one causing them.
     */
    private Result forwarded(VerifiedSignature signer, ActivityEnvelope envelope, int bodyBytes) {
        if (envelope.id() == null) {
            log.debug("Ignoring a {} from {}, signed by {}, which has no ID to fetch it by", envelope.type(),
                    envelope.actor(), signer.owner());
            return Result.of(Outcome.IGNORED);
        }
        var parsedId = ActivityObject.parseUri(envelope.id());
        var actor = ActivityObject.parseUri(envelope.actor());
        if (parsedId.isEmpty()
                || actor.isEmpty()
                || !isOnActorsServer(parsedId.get(), actor.get())
                || !InboundActivity.isStorableId(envelope.id())
                || RemoteActorDocument.isOnDomain(parsedId.get(), instance.domain())
                || blocks.isBlocked(parsedId.get().getHost())
                || actors.isInactive(actor.get())) {
            log.debug("Ignoring activity {} from {}, signed by {}, which can't be fetched from its origin",
                    envelope.id(), envelope.actor(), signer.owner());
            return Result.of(Outcome.IGNORED);
        }
        return admitted(signer.owner(), bodyBytes, () -> {
            if (!stored.exists(envelope.id())) {
                forwarded.queue(envelope.id(), actor.get(), envelope.type(), checkedObject(envelope, actor.get()));
            }
        }, Outcome.FORWARDED);
    }

    /**
     * @return a {@code Delete}'s, a {@code Create}'s, or an {@code Update}'s object, if it's on the actor's server, which is
     * the only one that can say it's gone, or serve it, when the origin doesn't serve the activity. An {@code Update} of the
     * actor itself has none, since only a note is fetched in its place.
     */
    private Optional<URI> checkedObject(ActivityEnvelope envelope, URI actor) {
        var object = switch (envelope.type()) {
            case "Delete" -> DeleteHandler.objectOf(envelope.body(), json);
            case "Create", "Update" -> ActivityObject.of(envelope.body(), json)
                    .map(ActivityObject::id)
                    .filter(InboundActivity::isStorableId)
                    .filter(id -> !id.equals(actor.toString()))
                    .flatMap(ActivityObject::parseUri);
            case null, default -> Optional.<URI>empty();
        };
        return object.filter(id -> isOnActorsServer(id, actor));
    }

    /**
     * An ID on the actor's server for an activity that came without one, which is only taken from its signer. It's the same
     * for the same body, so a repeat is stored as one, and {@link InboundActivityHandler#acceptsWithoutId} explains why
     * it's still processed.
     */
    private static String anonymousId(ActivityEnvelope envelope) {
        return envelope.actor() + "#" + envelope.type().toLowerCase(Locale.ROOT) + "/"
                + Digests.sha256Hex(envelope.body());
    }

    /**
     * Runs the step that keeps the activity, if the signer's server is within its limits.
     */
    private Result admitted(URI sender, int bodyBytes, Runnable keep, Outcome outcome) {
        var admission = limits.tryAdmit(sender, bodyBytes);
        if (admission instanceof ServerActivityLimits.Admission.Refused(var retryAfter)) {
            log.debug("Refusing an activity from {}, whose server is over its limit", sender);
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
