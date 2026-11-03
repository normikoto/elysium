package dev.biribiri.elysium.actor.signing;

import java.security.PrivateKey;

/**
 * @param keyId the ID other servers use to find the matching public key
 */
public record SigningKey(String keyId, PrivateKey privateKey) {

    /**
     * Leaves out the key, because what a key's own {@code toString} prints depends on the security provider.
     */
    @Override
    public String toString() {
        return "SigningKey[keyId=" + keyId + "]";
    }
}
