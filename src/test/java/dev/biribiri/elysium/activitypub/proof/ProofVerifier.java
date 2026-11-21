package dev.biribiri.elysium.activitypub.proof;

import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Verifies an {@code eddsa-jcs-2022} proof by the steps of the spec's Verify Proof algorithm, written apart from
 * {@link DataIntegrityProofs}, so that a test of signing doesn't only check that it agrees with itself.
 */
final class ProofVerifier {

    private ProofVerifier() {
    }

    static boolean verifies(ObjectNode secured, String publicKeyPem) throws Exception {
        var unsecured = secured.deepCopy();
        var options = (ObjectNode) unsecured.remove("proof").deepCopy();
        var proofValue = options.remove("proofValue").stringValue();
        if (!options.get("type").stringValue().equals("DataIntegrityProof")
                || !options.get("cryptosuite").stringValue().equals("eddsa-jcs-2022")
                || !proofValue.startsWith("z")) {
            return false;
        }
        if (options.has("@context")) {
            var context = secured.get("@context");
            var expected = options.get("@context");
            for (var i = 0; i < expected.size(); i++) {
                if (!context.isArray() || i >= context.size() || !context.get(i).equals(expected.get(i))) {
                    return false;
                }
            }
            unsecured.set("@context", expected);
        }
        var hashData = new byte[64];
        System.arraycopy(sha256(Jcs.canonicalize(options)), 0, hashData, 0, 32);
        System.arraycopy(sha256(Jcs.canonicalize(unsecured)), 0, hashData, 32, 32);
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey(publicKeyPem));
        verifier.update(hashData);
        return verifier.verify(decodeBase58(proofValue.substring(1)));
    }

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static PublicKey publicKey(String pem) throws Exception {
        var der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] decodeBase58(String text) {
        var alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        var value = BigInteger.ZERO;
        var zeros = 0;
        while (zeros < text.length() && text.charAt(zeros) == '1') {
            zeros++;
        }
        for (var c : text.toCharArray()) {
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c)));
        }
        var bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        var result = new byte[zeros + bytes.length];
        System.arraycopy(bytes, 0, result, zeros, bytes.length);
        return result;
    }
}
