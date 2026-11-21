package dev.biribiri.elysium.activitypub.signature;

import java.net.URI;
import java.security.PublicKey;

/**
 * A public key, and the actor it's been confirmed to belong to.
 *
 * @param use what the actor's document lists the key for, which is all it can be used for
 */
record ResolvedKey(String keyId, URI owner, PublicKey key, Use use) {

    enum Use {
        /** Listed in {@code publicKey}. */
        HTTP_SIGNATURES,
        /** Listed in {@code assertionMethod}, as FEP-521a describes. */
        INTEGRITY_PROOFS
    }
}
