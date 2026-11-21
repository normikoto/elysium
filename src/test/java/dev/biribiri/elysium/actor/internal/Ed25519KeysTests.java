package dev.biribiri.elysium.actor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ed25519KeysTests {

    @Test
    void theGeneratedPrivateKeyMatchesThePublicKey() throws Exception {
        var keys = Ed25519Keys.generate();
        var data = "data".getBytes(StandardCharsets.UTF_8);

        var signer = Signature.getInstance("Ed25519");
        signer.initSign(Ed25519Keys.parsePrivateKey(keys.privateKeyPem()));
        signer.update(data);
        var signature = signer.sign();

        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey(keys.publicKeyPem()));
        verifier.update(data);
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void generatesADifferentKeyEachTime() {
        assertThat(Ed25519Keys.generate().publicKeyPem()).isNotEqualTo(Ed25519Keys.generate().publicKeyPem());
    }

    @Test
    void leavesThePrivateKeyOutOfItsStringForm() {
        var keys = Ed25519Keys.generate();

        assertThat(keys.toString()).doesNotContain("PRIVATE KEY", keys.privateKeyPem());
    }

    @Test
    void rejectsAnRsaKey() {
        assertThatThrownBy(() -> Ed25519Keys.parsePrivateKey(RsaKeys.generate().privateKeyPem()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a key",
            "-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----",
            "-----BEGIN PRIVATE KEY-----\nc2VjcmV0IHZhbHVl\n-----END PRIVATE KEY-----"
    })
    void rejectsAnythingElseWithoutRepeatingIt(String pem) {
        assertThatThrownBy(() -> Ed25519Keys.parsePrivateKey(pem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageNotContaining("c2VjcmV0");
    }

    static PublicKey publicKey(String pem) throws Exception {
        var der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }
}
