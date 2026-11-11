package dev.biribiri.elysium.auth.internal;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;

import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Time-based one-time passwords, RFC 6238. These use SHA-1, six digits, and 30 seconds, because some authenticator apps
 * ignore any other settings in the QR code and would show codes that never match.
 */
final class TotpCodes {

    static final Duration TIME_STEP = TimeBasedOneTimePasswordGenerator.DEFAULT_TIME_STEP;
    static final int DIGITS = 6;

    /**
     * RFC 4226 recommends a 160-bit key, the size of SHA-1's output.
     */
    private static final int SECRET_BYTES = 20;

    /**
     * How many steps either side of now a code may be from, which allows for a phone's clock being off and for typing slowly.
     */
    private static final int ALLOWED_DRIFT = 1;

    private static final TimeBasedOneTimePasswordGenerator GENERATOR =
            new TimeBasedOneTimePasswordGenerator(TIME_STEP, DIGITS);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpCodes() {
    }

    static byte[] newSecret() {
        var secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static long step(Instant time) {
        return time.getEpochSecond() / TIME_STEP.toSeconds();
    }

    /**
     * @return whether something someone typed has the shape of a code, rather than of a recovery code
     */
    static boolean looksLikeCode(String typed) {
        return typed.length() == DIGITS && typed.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /**
     * @return the time step that a code is for, if it's within {@link #ALLOWED_DRIFT} of now. Which one it is matters,
     * because a code is only accepted once, and so are the ones before it.
     */
    static OptionalLong matchingStep(byte[] secret, String code, Instant now) {
        if (!looksLikeCode(code)) {
            return OptionalLong.empty();
        }
        var typed = code.getBytes(StandardCharsets.US_ASCII);
        var current = step(now);
        var match = OptionalLong.empty();
        // Every step is checked, even after a match, so that how long this takes doesn't say which one matched.
        for (long step = current - ALLOWED_DRIFT; step <= current + ALLOWED_DRIFT; step++) {
            if (MessageDigest.isEqual(typed, code(secret, step).getBytes(StandardCharsets.US_ASCII)) && match.isEmpty()) {
                match = OptionalLong.of(step);
            }
        }
        return match;
    }

    static String code(byte[] secret, long step) {
        try {
            var value = GENERATOR.generateOneTimePassword(
                    new SecretKeySpec(secret, GENERATOR.getAlgorithm()), Instant.ofEpochSecond(step * TIME_STEP.toSeconds()));
            return String.format("%0" + DIGITS + "d", value);
        } catch (InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The link that an authenticator app reads from the QR code. The label is the account, prefixed by the issuer, which apps
     * show it under. The issuer is the server's host without a port, since a colon would end it early.
     */
    static String otpAuthUri(byte[] secret, String host, String account) {
        return "otpauth://totp/" + encode(host) + ":" + encode(account)
                + "?secret=" + Base32.encode(secret)
                + "&issuer=" + encode(host)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + TIME_STEP.toSeconds();
    }

    private static String encode(String value) {
        // URLEncoder is for forms, where a space is a plus, but in a URI's path that's a literal plus.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
