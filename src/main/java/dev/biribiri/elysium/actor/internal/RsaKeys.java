package dev.biribiri.elysium.actor.internal;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;

/**
 * An RSA keypair in PEM form, which signs HTTP requests.
 */
public record RsaKeys(String publicKeyPem, String privateKeyPem) {

    private static final int KEY_SIZE_BITS = 2048;

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
                    Pem.publicKey(keyPair.getPublic().getEncoded()),
                    Pem.privateKey(keyPair.getPrivate().getEncoded()));
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
        return Pem.parsePrivateKey(pem, "RSA");
    }
}
