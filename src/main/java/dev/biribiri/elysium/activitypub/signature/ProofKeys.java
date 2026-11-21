package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.security.PublicKey;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds the key that an integrity proof names in its {@code verificationMethod}, through the same lookups, limits, and
 * cache as the keys of HTTP signatures, and with the same checks on who owns it. Only keys an actor lists in
 * {@code assertionMethod} count.
 */
@Component
public class ProofKeys {

    private static final Logger log = LoggerFactory.getLogger(ProofKeys.class);

    private final PublicKeyResolver keys;
    private final InstanceProperties instance;
    private final DomainBlocks blocks;
    private final ActorService actors;

    ProofKeys(PublicKeyResolver keys, InstanceProperties instance, DomainBlocks blocks, ActorService actors) {
        this.keys = keys;
        this.instance = instance;
        this.blocks = blocks;
        this.actors = actors;
    }

    /**
     * Blocks while fetching the key, so this mustn't be called inside a database transaction.
     *
     * @param keyId     the proof's {@code verificationMethod}
     * @param signature whether the proof's signature matches a key. If it doesn't match the key that's cached, the key
     *                  is fetched again, since it may have changed.
     * @param budget    the key fetches that the request's sender is allowed to cause
     * @return the actor that owns the key, or empty if the proof can't be accepted for any reason, including a key that
     *         can't be fetched right now. Callers still have to check that this actor can speak for what was signed.
     */
    public Optional<URI> verify(String keyId, Predicate<PublicKey> signature, KeyFetchBudget budget) {
        try {
            var parsed = KeyId.parse(keyId, instance);
            // Activities from this server's own actors don't arrive from other servers.
            if (parsed.local() || blocks.isBlocked(parsed.host()) || actors.isInactive(parsed.document())) {
                return Optional.empty();
            }
            var key = keys.resolve(parsed, budget);
            if (!(isForProofs(key) && signature.test(key.key()))) {
                key = keys.refresh(parsed, key, budget)
                        .filter(refreshed -> isForProofs(refreshed) && signature.test(refreshed.key()))
                        .orElse(null);
            }
            if (key == null || actors.isInactive(key.owner())) {
                return Optional.empty();
            }
            return Optional.of(key.owner());
        } catch (SignatureVerificationException e) {
            log.debug("Not accepting a proof made with {}: {}", keyId, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isForProofs(ResolvedKey key) {
        return key.use() == ResolvedKey.Use.INTEGRITY_PROOFS;
    }
}
