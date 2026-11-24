package dev.biribiri.elysium.push.internal;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Encrypts a message for a browser or app, which only it can read, as Web Push requires. A fresh key pair and salt are
 * used for each message.
 *
 * <p>There are two encodings. {@code aes128gcm} is RFC 8291's. {@code aesgcm} is the draft before it, which most
 * Mastodon clients still expect, since Mastodon only used it until 4.4, and the key and salt go in headers instead.
 */
public final class WebPushEncryption {

    /**
     * The size of the only record, which is RFC 8291's recommendation.
     */
    private static final int RECORD_SIZE = 4096;
    private static final int TAG_LENGTH = 16;
    private static final int SALT_LENGTH = 16;

    /**
     * Push services only have to accept 4096 bytes, and that's what's left of them in {@code aes128gcm} after the header,
     * the delimiter, and the tag. {@code aesgcm} has a little more room.
     */
    public static final int MAX_PLAINTEXT = 3993;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    /**
     * @param body      what to send
     * @param salt      which {@code aesgcm} sends in the {@code Encryption} header
     * @param serverKey the ephemeral public key, which {@code aesgcm} sends in the {@code Crypto-Key} header
     */
    public record Encrypted(byte[] body, byte[] salt, byte[] serverKey) {
    }

    public static Encrypted aes128gcm(byte[] plaintext, ECPublicKey receiver, byte[] authSecret) {
        return aes128gcm(plaintext, receiver, authSecret, P256.generate(), randomSalt());
    }

    static Encrypted aes128gcm(byte[] plaintext, ECPublicKey receiver, byte[] authSecret, KeyPair ephemeral, byte[] salt) {
        checkLength(plaintext);
        var receiverKey = P256.encode(receiver);
        var serverKey = P256.encode((ECPublicKey) ephemeral.getPublic());
        var secret = agree(ephemeral, receiver);

        var keyInfo = concat(ascii("WebPush: info\0"), receiverKey, serverKey);
        var ikm = hkdf(authSecret, secret, keyInfo, 32);
        var cek = hkdf(salt, ikm, ascii("Content-Encoding: aes128gcm\0"), 16);
        var nonce = hkdf(salt, ikm, ascii("Content-Encoding: nonce\0"), 12);

        // The 2 marks the last record. There's no padding after it.
        var padded = concat(plaintext, new byte[]{2});
        var ciphertext = seal(cek, nonce, padded);
        var header = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + serverKey.length)
                .put(salt)
                .putInt(RECORD_SIZE)
                .put((byte) serverKey.length)
                .put(serverKey)
                .array();
        return new Encrypted(concat(header, ciphertext), salt, serverKey);
    }

    public static Encrypted aesgcm(byte[] plaintext, ECPublicKey receiver, byte[] authSecret) {
        return aesgcm(plaintext, receiver, authSecret, P256.generate(), randomSalt());
    }

    static Encrypted aesgcm(byte[] plaintext, ECPublicKey receiver, byte[] authSecret, KeyPair ephemeral, byte[] salt) {
        checkLength(plaintext);
        var receiverKey = P256.encode(receiver);
        var serverKey = P256.encode((ECPublicKey) ephemeral.getPublic());
        var secret = agree(ephemeral, receiver);

        var ikm = hkdf(authSecret, secret, ascii("Content-Encoding: auth\0"), 32);
        var context = concat(ascii("P-256\0"), lengthPrefixed(receiverKey), lengthPrefixed(serverKey));
        var cek = hkdf(salt, ikm, concat(ascii("Content-Encoding: aesgcm\0"), context), 16);
        var nonce = hkdf(salt, ikm, concat(ascii("Content-Encoding: nonce\0"), context), 12);

        // Two bytes saying how much padding follows, which is none.
        var padded = concat(new byte[2], plaintext);
        return new Encrypted(seal(cek, nonce, padded), salt, serverKey);
    }

    private static void checkLength(byte[] plaintext) {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("A push message can't be longer than " + MAX_PLAINTEXT + " bytes");
        }
    }

    private static byte[] randomSalt() {
        var salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    private static byte[] agree(KeyPair ephemeral, ECPublicKey receiver) {
        try {
            var agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ephemeral.getPrivate());
            agreement.doPhase(receiver, true);
            return agreement.generateSecret();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * RFC 5869's HKDF with SHA-256, for at most one block of output, which is all Web Push needs.
     */
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        var prk = hmac(salt, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH * 8, nonce));
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] lengthPrefixed(byte[] value) {
        return ByteBuffer.allocate(2 + value.length).putShort((short) value.length).put(value).array();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
