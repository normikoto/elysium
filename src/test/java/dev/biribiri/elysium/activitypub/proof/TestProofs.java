package dev.biribiri.elysium.activitypub.proof;

import dev.biribiri.elysium.actor.signing.SigningKey;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * Signs activities as another server would, for tests outside this package.
 */
public final class TestProofs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestProofs() {
    }

    /**
     * @param key an Ed25519 key, whose ID becomes the proof's {@code verificationMethod}
     */
    public static String sign(String activity, SigningKey key) {
        return sign(activity, key, Instant.now());
    }

    /**
     * @param created when the proof says it was made
     */
    public static String sign(String activity, SigningKey key, Instant created) {
        var signed = DataIntegrityProofs.addProof((ObjectNode) JSON.readTree(activity), key, created);
        return JSON.writeValueAsString(signed);
    }
}
