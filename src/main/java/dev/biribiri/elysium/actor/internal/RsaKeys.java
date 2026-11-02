package dev.biribiri.elysium.actor.internal;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * An RSA keypair in PEM form: SubjectPublicKeyInfo for the public key and PKCS#8 for the private key.
 */
public record RsaKeys(String publicKeyPem, String privateKeyPem) {

    private static final int KEY_SIZE_BITS = 2048;

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

    private static String pem(String label, byte[] der) {
        var encoder = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        return "-----BEGIN " + label + "-----\n" + encoder.encodeToString(der) + "\n-----END " + label + "-----\n";
    }
}
