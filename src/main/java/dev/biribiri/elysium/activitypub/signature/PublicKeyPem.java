package dev.biribiri.elysium.activitypub.signature;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Reads public keys from other servers, which aren't trusted to send anything sensible.
 */
final class PublicKeyPem {

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    /**
     * Smaller keys are too weak to trust, and much larger ones make each check slow enough to be a way to tie up this
     * server.
     */
    private static final int MIN_RSA_BITS = 2048;
    private static final int MAX_RSA_BITS = 8192;

    private PublicKeyPem() {
    }

    /**
     * @throws IllegalArgumentException if this isn't an RSA key in SubjectPublicKeyInfo form, of a suitable size
     */
    static PublicKey parse(String pem) {
        var text = pem.strip();
        if (!text.startsWith(BEGIN) || !text.endsWith(END)) {
            throw new IllegalArgumentException("Public key isn't in SubjectPublicKeyInfo PEM form");
        }
        var body = text.substring(BEGIN.length(), text.length() - END.length());
        try {
            var der = Base64.getMimeDecoder().decode(body);
            var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            var bits = ((RSAPublicKey) key).getModulus().bitLength();
            if (bits < MIN_RSA_BITS || bits > MAX_RSA_BITS) {
                throw new IllegalArgumentException("RSA public key has an unsuitable size: " + bits);
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Public key isn't a valid RSA key", e);
        }
    }
}
