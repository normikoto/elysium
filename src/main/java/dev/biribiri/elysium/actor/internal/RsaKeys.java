package dev.biribiri.elysium.actor.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * An RSA keypair in PEM form: SubjectPublicKeyInfo for the public key and PKCS#8 for the private key.
 */
public record RsaKeys(String publicKeyPem, String privateKeyPem) {

    private static final int KEY_SIZE_BITS = 2048;
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";

    /**
     * Leaves out the private key, so that it can't end up in a log.
     */
    @Override
    public String toString() {
        return "RsaKeys[publicKeyPem=" + publicKeyPem + "]";
    }

    public static RsaKeys generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            var keyPair = generator.generateKeyPair();
            return new RsaKeys(
                    pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                    pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available", e);
        }
    }

    /**
     * Reads a private key in the form {@link #generate()} writes it.
     *
     * @throws IllegalArgumentException if it isn't one. The message doesn't include any of the value.
     */
    public static PrivateKey parsePrivateKey(String pem) {
        var body = pem.strip();
        if (!body.startsWith(PRIVATE_KEY_BEGIN) || !body.endsWith(PRIVATE_KEY_END)) {
            throw new IllegalArgumentException("Not a PKCS#8 private key");
        }
        body = body.substring(PRIVATE_KEY_BEGIN.length(), body.length() - PRIVATE_KEY_END.length());
        try {
            var der = Base64.getMimeDecoder().decode(body);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The cause isn't kept, because its message could include part of the key.
            throw new IllegalArgumentException("Not a valid RSA private key");
        }
    }

    private static String pem(String label, byte[] der) {
        var encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        return "-----BEGIN " + label + "-----\n" + encoder.encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
