package dev.biribiri.elysium.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Codes that work like passwords, such as invites and email confirmation links. Only their hashes are stored, so that
 * reading the database doesn't give anyone a working one. They're long and random, so a plain hash is enough: there's
 * nothing to guess that a slow hash would protect.
 */
final class SecretCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretCodes() {
    }

    static String generate() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String code) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
