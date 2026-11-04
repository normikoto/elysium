package dev.biribiri.elysium.actor.signing;

import dev.biribiri.elysium.actor.internal.RsaKeys;
import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPrivateKey;

import static org.assertj.core.api.Assertions.assertThat;

class SigningKeyTests {

    @Test
    void leavesTheKeyOutOfItsStringForm() {
        var privateKey = (RSAPrivateKey) RsaKeys.parsePrivateKey(RsaKeys.generate().privateKeyPem());

        var text = new SigningKey("https://example.test/users/alice#main-key", privateKey).toString();

        assertThat(text)
                .contains("https://example.test/users/alice#main-key")
                .doesNotContain(privateKey.getPrivateExponent().toString())
                .doesNotContain(privateKey.getPrivateExponent().toString(16))
                .doesNotContain(privateKey.getClass().getSimpleName());
    }
}
