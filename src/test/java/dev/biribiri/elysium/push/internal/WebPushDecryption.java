package dev.biribiri.elysium.push.internal;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Reads messages as a browser would, written separately from {@link WebPushEncryption} so that a mistake in one shows.
 */
public final class WebPushDecryption {

    private WebPushDecryption() {
    }

    public static byte[] base64Url(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public static PrivateKey privateKey(byte[] scalar, ECPublicKey sameCurve) throws Exception {
        return KeyFactory.getInstance("EC")
                .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), sameCurve.getParams()));
    }

    public static KeyPair keyPair(String privateBase64Url, String publicBase64Url) throws Exception {
        var publicKey = P256.decodePublic(base64Url(publicBase64Url));
        return new KeyPair(publicKey, privateKey(base64Url(privateBase64Url), publicKey));
    }

    public static byte[] aes128gcm(byte[] body, KeyPair receiver, byte[] authSecret) throws Exception {
        var buffer = ByteBuffer.wrap(body);
        var salt = new byte[16];
        buffer.get(salt);
        buffer.getInt();
        var senderKey = new byte[buffer.get()];
        buffer.get(senderKey);
        var ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        var receiverKey = P256.encode((ECPublicKey) receiver.getPublic());
        var secret = agree(receiver, senderKey);
        var ikm = WebPushEncryption.hkdf(authSecret, secret,
                concat(ascii("WebPush: info\0"), receiverKey, senderKey), 32);
        var cek = WebPushEncryption.hkdf(salt, ikm, ascii("Content-Encoding: aes128gcm\0"), 16);
        var nonce = WebPushEncryption.hkdf(salt, ikm, ascii("Content-Encoding: nonce\0"), 12);
        var padded = open(cek, nonce, ciphertext);
        var end = padded.length - 1;
        while (padded[end] == 0) end--;
        if (padded[end] != 2) throw new IllegalStateException("No last-record delimiter");
        return Arrays.copyOf(padded, end);
    }

    public static byte[] aesgcm(byte[] body, byte[] salt, byte[] senderKey, KeyPair receiver, byte[] authSecret)
            throws Exception {
        var receiverKey = P256.encode((ECPublicKey) receiver.getPublic());
        var secret = agree(receiver, senderKey);
        var ikm = WebPushEncryption.hkdf(authSecret, secret, ascii("Content-Encoding: auth\0"), 32);
        var context = concat(ascii("P-256\0"), prefixed(receiverKey), prefixed(senderKey));
        var cek = WebPushEncryption.hkdf(salt, ikm, concat(ascii("Content-Encoding: aesgcm\0"), context), 16);
        var nonce = WebPushEncryption.hkdf(salt, ikm, concat(ascii("Content-Encoding: nonce\0"), context), 12);
        var padded = open(cek, nonce, body);
        var padding = ((padded[0] & 0xff) << 8) | (padded[1] & 0xff);
        return Arrays.copyOfRange(padded, 2 + padding, padded.length);
    }

    private static byte[] agree(KeyPair receiver, byte[] senderKey) throws Exception {
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(receiver.getPrivate());
        agreement.doPhase(P256.decodePublic(senderKey), true);
        return agreement.generateSecret();
    }

    private static byte[] open(byte[] key, byte[] nonce, byte[] ciphertext) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] prefixed(byte[] value) {
        return ByteBuffer.allocate(2 + value.length).putShort((short) value.length).put(value).array();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
