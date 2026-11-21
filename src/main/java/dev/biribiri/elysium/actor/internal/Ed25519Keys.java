package dev.biribiri.elysium.actor.internal;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;

/**
 * An Ed25519 keypair in PEM form, which signs integrity proofs on activities (FEP-8b32). It's separate from the RSA key,
 * because the {@code eddsa-jcs-2022} cryptosuite only allows Ed25519, while servers only accept RSA for HTTP signatures.
 */
public record Ed25519Keys(String publicKeyPem, String privateKeyPem) {

    /**
     * Leaves out the private key, so that it can't end up in a log.
     */
    @Override
    public String toString() {
        return "Ed25519Keys[publicKeyPem=" + publicKeyPem + "]";
    }

    public static Ed25519Keys generate() {
        try {
            var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new Ed25519Keys(
                    Pem.publicKey(keyPair.getPublic().getEncoded()),
                    Pem.privateKey(keyPair.getPrivate().getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    /**
     * Reads a private key in the form {@link #generate()} writes it.
     *
     * @throws IllegalArgumentException if it isn't one. The message doesn't include any of the value.
     */
    public static PrivateKey parsePrivateKey(String pem) {
        return Pem.parsePrivateKey(pem, "Ed25519");
    }
}
