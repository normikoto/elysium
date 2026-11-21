package dev.biribiri.elysium.activitypub.vocab;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultikeyTests {

    private static final URI ALICE = URI.create("https://elysium.example/users/alice");

    @Test
    void encodesAnEd25519KeyAsFep521aDescribes() throws Exception {
        var publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();

        var key = Multikey.ed25519(ALICE + "#ed25519-key", ALICE, pem(publicKey.getEncoded()));

        assertThat(key.id()).isEqualTo(ALICE + "#ed25519-key");
        assertThat(key.type()).isEqualTo("Multikey");
        assertThat(key.controller()).isEqualTo(ALICE);
        // Every Ed25519 key starts like this once it has its prefix.
        assertThat(key.publicKeyMultibase()).startsWith("z6Mk");
        var decoded = decodeBase58(key.publicKeyMultibase().substring(1));
        var spki = publicKey.getEncoded();
        assertThat(decoded).hasSize(34);
        assertThat(Arrays.copyOf(decoded, 2)).containsExactly(0xed, 0x01);
        assertThat(Arrays.copyOfRange(decoded, 2, 34)).isEqualTo(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
    }

    @Test
    void refusesAnRsaKey() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();

        assertThatThrownBy(() -> Multikey.ed25519(ALICE + "#ed25519-key", ALICE, pem(rsa.getEncoded())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String pem(byte[] der) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * Written separately from {@link Base58}, so that this test doesn't only check that it agrees with itself.
     */
    private static byte[] decodeBase58(String text) {
        var alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        var value = BigInteger.ZERO;
        for (var c : text.toCharArray()) {
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c)));
        }
        var bytes = value.toByteArray();
        // BigInteger adds a zero byte when the top bit is set. A key's first byte, 0xed, has it set.
        return bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
