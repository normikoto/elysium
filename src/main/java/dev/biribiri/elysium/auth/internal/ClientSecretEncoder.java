package dev.biribiri.elysium.auth.internal;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes client secrets with SHA-256. They're 256-bit random values, so they can't be guessed and don't need the slow
 * hashing that passwords do, which would only make client-authenticated endpoints cheaper to overload.
 *
 * <p>This must not be a bean, because Spring Authorization Server only finds the password encoder if there's exactly
 * one. It also can't be part of the password encoder: Spring would see a hash that isn't the default kind and replace
 * it with one that is.
 */
public final class ClientSecretEncoder implements PasswordEncoder {

    public static final ClientSecretEncoder INSTANCE = new ClientSecretEncoder();

    private static final String PREFIX = "{sha256}";

    private ClientSecretEncoder() {
    }

    @Override
    public String encode(CharSequence secret) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(secret.toString().getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @Override
    public boolean matches(CharSequence secret, String encoded) {
        if (secret == null || encoded == null) {
            return false;
        }
        return MessageDigest.isEqual(
                encode(secret).getBytes(StandardCharsets.UTF_8),
                encoded.getBytes(StandardCharsets.UTF_8));
    }
}
