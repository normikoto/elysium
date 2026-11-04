package dev.biribiri.elysium.activitypub.signature;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicKeyPemTests {

    @Test
    void readsAnRsaKey() {
        assertThat(PublicKeyPem.parse(SignatureFixtures.PUBLIC_KEY_PEM).getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    void readsAKeyWithWindowsLineEndings() {
        var pem = SignatureFixtures.PUBLIC_KEY_PEM.replace("\n", "\r\n");

        assertThat(PublicKeyPem.parse(pem)).isEqualTo(SignatureFixtures.publicKey());
    }

    @Test
    void rejectsKeysThatAreTooSmall() {
        assertThatThrownBy(() -> PublicKeyPem.parse(SignatureFixtures.newPublicKeyPem(1024)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not a key",
            "-----BEGIN PUBLIC KEY-----\nbm90IGEga2V5\n-----END PUBLIC KEY-----",
            "-----BEGIN RSA PUBLIC KEY-----\nbm90IGEga2V5\n-----END RSA PUBLIC KEY-----"
    })
    void rejectsAnythingElse(String pem) {
        assertThatThrownBy(() -> PublicKeyPem.parse(pem)).isInstanceOf(IllegalArgumentException.class);
    }
}
