package dev.biribiri.elysium.activitypub.vocab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

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

    @Test
    void readsBackTheKeyItEncodes() throws Exception {
        var publicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        var key = Multikey.ed25519(ALICE + "#ed25519-key", ALICE, pem(publicKey.getEncoded()));

        assertThat(Multikey.parseEd25519(key.publicKeyMultibase())).isEqualTo(publicKey);
    }

    /**
     * The key from the test vectors in W3C's Data Integrity EdDSA Cryptosuites, and FEP-521a's example.
     */
    @Test
    void readsAKeyFromAnotherImplementation() {
        var key = Multikey.parseEd25519("z6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2");

        var spki = key.getEncoded();
        assertThat(HexFormat.of().formatHex(Arrays.copyOfRange(spki, spki.length - 32, spki.length)))
                .isEqualTo("b00d8d938e7f773d51565aad36a623f5344f7f5d1960f9cf3e8e12620ea2810f");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2",
            "z6MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ",
            "z0MkrJVnaZkeFzdQyMZu1cgjg7k1pZZ6pvBQ7XJPt4swbTQ2",
            // A secp256k1 key, whose multicodec prefix is 0xe7.
            "zQ3shokFTS3brHcDQrn82RUDfCZESWL1ZdCEJwekUDPQiYBme"
    })
    void refusesAnythingButAnEd25519Key(String multibase) {
        assertThatThrownBy(() -> Multikey.parseEd25519(multibase)).isInstanceOf(IllegalArgumentException.class);
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
