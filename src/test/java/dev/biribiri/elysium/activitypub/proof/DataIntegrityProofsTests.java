package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.actor.signing.SigningKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import static dev.biribiri.elysium.activitypub.proof.JcsTests.resource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks against the {@code eddsa-jcs-2022} test vectors in W3C's Data Integrity EdDSA Cryptosuites, from
 * https://github.com/w3c/vc-di-eddsa/tree/main/TestVectors. Ed25519 signatures don't vary, so the proof has to match theirs
 * exactly.
 */
class DataIntegrityProofsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String PUBLIC_KEY_MULTIBASE = "z6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2";
    private static final String KEY_ID = "did:key:" + PUBLIC_KEY_MULTIBASE + "#" + PUBLIC_KEY_MULTIBASE;

    /**
     * The test vectors' {@code secretKeyMultibase}, {@code z3u2en7t5LR2WtQH5PfFqMqwVHBeXouLzo6haApm8XHqvjxq}, decoded and
     * without its two-byte multicodec prefix.
     */
    private static final String SECRET_KEY_HEX = "c96ef9ea10c5e414c471723aff9de72c35fa5b70fae97e8832ecac7d2e2b8ed6";

    /**
     * The test vectors' {@code publicKeyMultibase}, decoded, without its prefix, and wrapped in SubjectPublicKeyInfo.
     */
    private static final String PUBLIC_KEY_PEM = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(
            HexFormat.of().parseHex("302a300506032b6570032100"
                    + "b00d8d938e7f773d51565aad36a623f5344f7f5d1960f9cf3e8e12620ea2810f"))
            + "\n-----END PUBLIC KEY-----\n";

    private static final Instant CREATED = Instant.parse("2023-02-24T23:36:38Z");

    @Test
    void makesTheSameProofAsTheTestVectors() throws Exception {
        var unsigned = (ObjectNode) JSON.readTree(resource("w3c-unsigned.json"));

        var signed = DataIntegrityProofs.addProof(unsigned, key(), CREATED);

        assertThat(signed).isEqualTo(JSON.readTree(resource("w3c-signed.json")));
    }

    /**
     * So that the tests that use {@link ProofVerifier} can rely on it.
     */
    @Test
    void theTestsVerifierAcceptsTheTestVectorsProof() throws Exception {
        var signed = (ObjectNode) JSON.readTree(resource("w3c-signed.json"));
        var changed = signed.deepCopy();
        changed.put("name", "Another Credential");

        assertThat(ProofVerifier.verifies(signed, PUBLIC_KEY_PEM)).isTrue();
        assertThat(ProofVerifier.verifies(changed, PUBLIC_KEY_PEM)).isFalse();
    }

    @Test
    void signsTheCanonicalFormsOfTheTestVectors() throws Exception {
        var unsigned = (ObjectNode) JSON.readTree(resource("w3c-unsigned.json"));

        var proof = (ObjectNode) DataIntegrityProofs.addProof(unsigned, key(), CREATED).get("proof").deepCopy();
        proof.remove("proofValue");

        assertThat(Jcs.canonicalize(unsigned)).isEqualTo(resource("w3c-canonical-document.txt"));
        assertThat(Jcs.canonicalize(proof)).isEqualTo(resource("w3c-canonical-proof-options.txt"));
    }

    @Test
    void leavesTheDocumentItWasGivenAsItWas() throws Exception {
        var unsigned = (ObjectNode) JSON.readTree(resource("w3c-unsigned.json"));
        var before = unsigned.deepCopy();

        DataIntegrityProofs.addProof(unsigned, key(), CREATED);

        assertThat(unsigned).isEqualTo(before);
    }

    @Test
    void writesCreatedInWholeSeconds() throws Exception {
        var unsigned = (ObjectNode) JSON.readTree(resource("w3c-unsigned.json"));

        var signed = DataIntegrityProofs.addProof(unsigned, key(), Instant.parse("2026-11-21T10:15:30.123456Z"));

        assertThat(signed.get("proof").get("created").stringValue()).isEqualTo("2026-11-21T10:15:30Z");
    }

    @Test
    void refusesADocumentThatAlreadyHasAProof() throws Exception {
        var signed = (ObjectNode) JSON.readTree(resource("w3c-signed.json"));

        assertThatThrownBy(() -> DataIntegrityProofs.addProof(signed, key(), CREATED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SigningKey key() throws Exception {
        return new SigningKey(KEY_ID, privateKey(HexFormat.of().parseHex(SECRET_KEY_HEX)));
    }

    /**
     * Wraps the 32 bytes in PKCS#8, as RFC 8410 has it for Ed25519.
     */
    static PrivateKey privateKey(byte[] secret) throws Exception {
        var prefix = HexFormat.of().parseHex("302e020100300506032b657004220420");
        var der = new byte[prefix.length + secret.length];
        System.arraycopy(prefix, 0, der, 0, prefix.length);
        System.arraycopy(secret, 0, der, prefix.length, secret.length);
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
