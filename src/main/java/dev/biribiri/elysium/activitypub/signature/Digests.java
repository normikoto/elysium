package dev.biribiri.elysium.activitypub.signature;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Digests {

    private Digests() {
    }

    static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
