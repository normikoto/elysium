package dev.biribiri.elysium.activitypub.signature;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

/**
 * The signature algorithms this server can check. These are chosen by this server, not by what a request asks for.
 */
enum SignatureAlgorithm {

    /** RSASSA-PKCS1-v1_5 with SHA-256. */
    RSA_SHA256("SHA256withRSA", "RSA");

    private final String jcaName;
    private final String keyAlgorithm;

    SignatureAlgorithm(String jcaName, String keyAlgorithm) {
        this.jcaName = jcaName;
        this.keyAlgorithm = keyAlgorithm;
    }

    /**
     * @return whether the signature is valid. A key of the wrong type isn't valid for this algorithm.
     */
    boolean verify(PublicKey key, byte[] signatureBase, byte[] signature) {
        if (!keyAlgorithm.equals(key.getAlgorithm())) {
            return false;
        }
        try {
            var verifier = Signature.getInstance(jcaName);
            verifier.initVerify(key);
            verifier.update(signatureBase);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    byte[] sign(PrivateKey key, byte[] signatureBase) {
        try {
            var signer = Signature.getInstance(jcaName);
            signer.initSign(key);
            signer.update(signatureBase);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Couldn't sign with " + jcaName, e);
        }
    }
}
