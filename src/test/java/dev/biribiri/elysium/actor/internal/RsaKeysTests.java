package dev.biribiri.elysium.actor.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaKeysTests {

    @Test
    void readsBackAGeneratedPrivateKey() throws Exception {
        var keys = RsaKeys.generate();

        var key = (RSAPrivateCrtKey) RsaKeys.parsePrivateKey(keys.privateKeyPem());

        var publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        var der = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        assertThat(keys.publicKeyPem().replaceAll("-----[A-Z ]+-----|\\s", "")).isEqualTo(der);
    }

    @Test
    void leavesThePrivateKeyOutOfItsStringForm() {
        var keys = RsaKeys.generate();

        assertThat(keys.toString()).doesNotContain("PRIVATE KEY", keys.privateKeyPem());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a key",
            "-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----",
            "-----BEGIN PRIVATE KEY-----\nc2VjcmV0IHZhbHVl\n-----END PRIVATE KEY-----"
    })
    void rejectsAnythingElseWithoutRepeatingIt(String pem) {
        assertThatThrownBy(() -> RsaKeys.parsePrivateKey(pem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageNotContaining("c2VjcmV0");
    }
}
