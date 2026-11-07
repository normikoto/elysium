package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Checks the signature on an incoming request. This doesn't depend on the signature format, which is up to each
 * {@link SignatureScheme}.
 *
 * <p>Checks that don't need the network come first, so that as few requests as possible make this server fetch a key.
 */
@Component
public class SignatureVerifier {

    /**
     * How long a signature lasts if it doesn't say. These values match Mastodon's, so that this server accepts what
     * Mastodon accepts. A request that passes can be sent again within this time, so callers have to cope with
     * duplicates.
     */
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);
    private static final Duration MAX_LIFETIME = Duration.ofHours(12);
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    private final List<SignatureScheme> schemes;
    private final PublicKeyResolver keys;
    private final InstanceProperties instance;
    private final DomainBlocks blocks;
    private final ActorService actors;
    private final Clock clock;

    @Autowired
    SignatureVerifier(
            List<SignatureScheme> schemes,
            PublicKeyResolver keys,
            InstanceProperties instance,
            DomainBlocks blocks,
            ActorService actors) {
        this(schemes, keys, instance, blocks, actors, Clock.systemUTC());
    }

    SignatureVerifier(
            List<SignatureScheme> schemes,
            PublicKeyResolver keys,
            InstanceProperties instance,
            DomainBlocks blocks,
            ActorService actors,
            Clock clock) {
        this.schemes = schemes;
        this.keys = keys;
        this.instance = instance;
        this.blocks = blocks;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * Checks a request's signature. This can block while fetching the sender's key, so it mustn't be called inside a
     * database transaction.
     *
     * @param budget the key fetches that the request's sender is allowed to cause
     * @return who signed the request. Callers still have to check that this actor can do what the request asks.
     * @throws SignatureVerificationException if the signature can't be accepted
     */
    public VerifiedSignature verify(HttpRequestData request, KeyFetchBudget budget) {
        var scheme = schemes.stream()
                .filter(candidate -> candidate.appliesTo(request))
                .findFirst()
                .orElseThrow(() -> new SignatureVerificationException(
                        Failure.NO_SUPPORTED_SIGNATURE, "Request has no signature in a supported format"));
        var claim = scheme.parse(request);
        checkTimeWindow(claim);

        var keyId = KeyId.parse(claim.keyId(), instance);
        // Before anything is fetched, so that a blocked server can't cause a request or use up any limits. This also covers
        // keys that were cached before the block.
        if (!keyId.local() && blocks.isBlocked(keyId.host())) {
            throw new SignatureVerificationException(Failure.DOMAIN_BLOCKED, "Key is on a blocked server: " + claim.keyId());
        }
        // Most servers' key IDs lead to the actor's own document, so this usually avoids fetching a suspended actor's key.
        if (!keyId.local() && actors.isInactive(keyId.document())) {
            throw suspended(keyId.document(), claim.keyId());
        }
        var key = keys.resolve(keyId, budget);
        if (!matches(claim, key)) {
            // The sender may have changed its key since this server fetched it.
            key = keys.refresh(keyId, key, budget)
                    .filter(refreshed -> matches(claim, refreshed))
                    .orElseThrow(() -> new SignatureVerificationException(
                            Failure.REJECTED, "Signature doesn't match the key " + claim.keyId()));
        }
        if (!keyId.local() && actors.isInactive(key.owner())) {
            throw suspended(key.owner(), claim.keyId());
        }
        return new VerifiedSignature(key.keyId(), key.owner());
    }

    private void checkTimeWindow(SignatureClaim claim) {
        var now = clock.instant();
        var created = claim.created();
        var longest = created.plus(MAX_LIFETIME);
        var expires = claim.expires() == null ? created.plus(DEFAULT_LIFETIME) : claim.expires();
        expires = expires.isAfter(longest) ? longest : expires;
        if (created.isAfter(now.plus(CLOCK_SKEW))) {
            throw expired("Signature was made in the future: " + created);
        }
        if (now.isAfter(expires.plus(CLOCK_SKEW))) {
            throw expired("Signature expired at " + expires);
        }
    }

    private static boolean matches(SignatureClaim claim, ResolvedKey key) {
        return claim.algorithm().verify(key.key(), claim.signatureBase(), claim.signature());
    }

    private static SignatureVerificationException suspended(URI actor, String keyId) {
        return SignatureVerificationException.suspended(actor, "Key belongs to a suspended or deleted actor: " + keyId);
    }

    private static SignatureVerificationException expired(String message) {
        return new SignatureVerificationException(Failure.REJECTED, message);
    }
}
