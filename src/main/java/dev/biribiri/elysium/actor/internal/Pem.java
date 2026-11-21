package dev.biribiri.elysium.actor.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Keys as stored in the database: SubjectPublicKeyInfo for public keys and PKCS#8 for private keys, in PEM form.
 */
final class Pem {

    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";

    private Pem() {
    }

    static String publicKey(byte[] der) {
        return encode("PUBLIC KEY", der);
    }

    static String privateKey(byte[] der) {
        return encode("PRIVATE KEY", der);
    }

    /**
     * @throws IllegalArgumentException if it isn't a PKCS#8 private key for this algorithm. The message doesn't include
     *                                  any of the value.
     */
    static PrivateKey parsePrivateKey(String pem, String algorithm) {
        var body = pem.strip();
        if (!body.startsWith(PRIVATE_KEY_BEGIN) || !body.endsWith(PRIVATE_KEY_END)) {
            throw new IllegalArgumentException("Not a PKCS#8 private key");
        }
        body = body.substring(PRIVATE_KEY_BEGIN.length(), body.length() - PRIVATE_KEY_END.length());
        try {
            var der = Base64.getMimeDecoder().decode(body);
            return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The cause isn't kept, because its message could include part of the key.
            throw new IllegalArgumentException("Not a valid " + algorithm + " private key");
        }
    }

    private static String encode(String label, byte[] der) {
        var encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        return "-----BEGIN " + label + "-----\n" + encoder.encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
