package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.activitypub.vocab.Base58;
import dev.biribiri.elysium.actor.signing.SigningKey;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Adds integrity proofs with the {@code eddsa-jcs-2022} cryptosuite, from W3C's Data Integrity EdDSA Cryptosuites,
 * which FEP-8b32 recommends.
 */
final class DataIntegrityProofs {

    static final String CRYPTOSUITE = "eddsa-jcs-2022";

    private DataIntegrityProofs() {
    }

    /**
     * @param unsecured the document, without a {@code proof}. It isn't changed.
     * @param key       an Ed25519 key, whose ID is the proof's {@code verificationMethod}
     * @return a copy of the document with a {@code proof}
     */
    static ObjectNode addProof(ObjectNode unsecured, SigningKey key, Instant created) {
        if (unsecured.has("proof")) {
            throw new IllegalArgumentException("The document already has a proof");
        }
        var proof = JsonNodeFactory.instance.objectNode()
                .put("type", "DataIntegrityProof")
                .put("cryptosuite", CRYPTOSUITE)
                .put("created", DateTimeFormatter.ISO_INSTANT.format(created.truncatedTo(ChronoUnit.SECONDS)))
                .put("verificationMethod", key.keyId())
                .put("proofPurpose", "assertionMethod");
        // The spec makes this optional, but strongly advises it, as verifiers check the document's @context against it.
        if (unsecured.has("@context")) {
            proof.set("@context", unsecured.get("@context").deepCopy());
        }
        var hashData = new byte[64];
        System.arraycopy(sha256(Jcs.canonicalize(proof)), 0, hashData, 0, 32);
        System.arraycopy(sha256(Jcs.canonicalize(unsecured)), 0, hashData, 32, 32);
        proof.put("proofValue", "z" + Base58.encode(sign(hashData, key)));
        var secured = unsecured.deepCopy();
        secured.set("proof", proof);
        return secured;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] sign(byte[] data, SigningKey key) {
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key.privateKey());
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 key: " + key, e);
        }
    }
}
