package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.activitypub.vocab.Base58;
import dev.biribiri.elysium.actor.signing.SigningKey;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Adds and checks integrity proofs with the {@code eddsa-jcs-2022} cryptosuite, from W3C's Data Integrity EdDSA
 * Cryptosuites, which FEP-8b32 recommends.
 */
final class DataIntegrityProofs {

    static final String CRYPTOSUITE = "eddsa-jcs-2022";

    /**
     * How far ahead of this server's clock a proof's {@code created} can be, as for HTTP signatures.
     */
    private static final Duration CLOCK_SKEW = Duration.ofHours(1);

    /**
     * An Ed25519 signature is 64 bytes, which is at most 88 characters of base58btc.
     */
    private static final int MAX_PROOF_VALUE_LENGTH = 100;

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
        proof.put("proofValue", "z" + Base58.encode(sign(hashData(proof, unsecured), key)));
        var secured = unsecured.deepCopy();
        secured.set("proof", proof);
        return secured;
    }

    /**
     * A proof whose key still has to be found.
     *
     * @param verificationMethod the ID of the key that made it
     * @param signature          whether a key made it
     */
    record Candidate(String verificationMethod, Predicate<PublicKey> signature) {
    }

    /**
     * Reads the document's proofs by the spec's Verify Proof steps, as far as they go without the key. Any that can't be
     * checked, such as one made with another cryptosuite, or one that's expired, is left out, as FEP-8b32 says to ignore
     * them.
     */
    static List<Candidate> candidates(ObjectNode secured, Instant now) {
        var proofs = secured.path("proof");
        var unsecured = secured.deepCopy();
        unsecured.remove("proof");
        // FEP-8b32 has a Linked Data Signature made after the proof, so the proof doesn't cover it.
        unsecured.remove("signature");
        var candidates = new ArrayList<Candidate>();
        for (var proof : proofs.isArray() ? proofs.valueStream().toList() : List.of(proofs)) {
            if (proof instanceof ObjectNode object) {
                candidate(object, secured, unsecured, now).ifPresent(candidates::add);
            }
        }
        return candidates;
    }

    private static Optional<Candidate> candidate(ObjectNode proof, ObjectNode secured, ObjectNode unsecured, Instant now) {
        if (!"DataIntegrityProof".equals(proof.path("type").stringValue(null))
                || !CRYPTOSUITE.equals(proof.path("cryptosuite").stringValue(null))
                || !"assertionMethod".equals(proof.path("proofPurpose").stringValue(null))
                || !proof.path("verificationMethod").isString()
                || !proof.path("proofValue").isString()) {
            return Optional.empty();
        }
        if (!isCurrent(proof, now)) {
            return Optional.empty();
        }
        var document = unsecured.deepCopy();
        var options = proof.deepCopy();
        var proofValue = options.remove("proofValue").stringValue();
        if (options.has("@context")) {
            if (!startsWith(secured.get("@context"), options.get("@context"))) {
                return Optional.empty();
            }
            document.set("@context", options.get("@context"));
        }
        byte[] signature;
        try {
            if (!proofValue.startsWith("z")) {
                return Optional.empty();
            }
            signature = Base58.decode(proofValue.substring(1), MAX_PROOF_VALUE_LENGTH);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        byte[] hashData;
        try {
            hashData = hashData(options, document);
        } catch (IllegalArgumentException e) {
            // JSON that RFC 8785 can't canonicalize, which the signer couldn't have signed either.
            return Optional.empty();
        }
        var verificationMethod = options.get("verificationMethod").stringValue();
        return Optional.of(new Candidate(verificationMethod, key -> verifies(hashData, signature, key)));
    }

    /**
     * The spec requires {@code created} to be a valid date and time when it's there. {@code expires} is from Data
     * Integrity, and FEP-8b32 says to ignore a proof that's past it.
     */
    private static boolean isCurrent(ObjectNode proof, Instant now) {
        try {
            if (proof.has("created")
                    && OffsetDateTime.parse(proof.get("created").asString()).toInstant().isAfter(now.plus(CLOCK_SKEW))) {
                return false;
            }
            return !proof.has("expires")
                    || !OffsetDateTime.parse(proof.get("expires").asString()).toInstant().isBefore(now.minus(CLOCK_SKEW));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * The spec has a verifier check this, so that the proof's {@code @context} can't change what the document means.
     */
    private static boolean startsWith(JsonNode documentContext, JsonNode proofContext) {
        var document = documentContext == null ? List.<JsonNode>of() : asList(documentContext);
        var proof = asList(proofContext);
        return document.size() >= proof.size() && document.subList(0, proof.size()).equals(proof);
    }

    private static List<JsonNode> asList(JsonNode context) {
        return context.isArray() ? context.valueStream().toList() : List.of(context);
    }

    private static byte[] hashData(ObjectNode options, ObjectNode document) {
        var hashData = new byte[64];
        System.arraycopy(sha256(Jcs.canonicalize(options)), 0, hashData, 0, 32);
        System.arraycopy(sha256(Jcs.canonicalize(document)), 0, hashData, 32, 32);
        return hashData;
    }

    private static boolean verifies(byte[] data, byte[] signature, PublicKey key) {
        if (!"Ed25519".equals(key.getAlgorithm()) && !"EdDSA".equals(key.getAlgorithm())) {
            return false;
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
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
