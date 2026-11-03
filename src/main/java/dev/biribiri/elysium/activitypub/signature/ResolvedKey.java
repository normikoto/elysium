package dev.biribiri.elysium.activitypub.signature;

import java.net.URI;
import java.security.PublicKey;

/**
 * A public key, and the actor it's been confirmed to belong to.
 */
record ResolvedKey(String keyId, URI owner, PublicKey key) {
}
