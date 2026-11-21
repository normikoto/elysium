package dev.biribiri.elysium.activitypub.vocab;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The examples are from the IETF draft that describes base58btc, draft-msporny-base58.
 */
class Base58Tests {

    @Test
    void encodesText() {
        assertThat(Base58.encode("Hello World!".getBytes(StandardCharsets.US_ASCII))).isEqualTo("2NEpo7TZRRrLZSi2U");
        assertThat(Base58.encode("The quick brown fox jumps over the lazy dog.".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("USm3fpXnKG5EUBx2ndxBDMPVciP5hGey2Jh4NDv6gmeo1LkMeiKrLJUUBk6Z");
    }

    @Test
    void keepsLeadingZeroBytes() {
        assertThat(Base58.encode(HexFormat.of().parseHex("0000287fb4cd"))).isEqualTo("11233QC4");
        assertThat(Base58.encode(new byte[2])).isEqualTo("11");
    }

    @Test
    void encodesNothingAsNothing() {
        assertThat(Base58.encode(new byte[0])).isEmpty();
    }

    @Test
    void decodesWhatItEncodes() {
        assertThat(Base58.decode("2NEpo7TZRRrLZSi2U", 100)).isEqualTo("Hello World!".getBytes(StandardCharsets.US_ASCII));
        assertThat(Base58.decode("11233QC4", 100)).isEqualTo(HexFormat.of().parseHex("0000287fb4cd"));
        assertThat(Base58.decode("11", 100)).isEqualTo(new byte[2]);
        assertThat(Base58.decode("", 100)).isEmpty();
        // The top bit of the first byte is set, which BigInteger would keep a sign byte for.
        assertThat(Base58.decode(Base58.encode(new byte[]{(byte) 0xed, 0x01}), 100))
                .isEqualTo(new byte[]{(byte) 0xed, 0x01});
    }

    @Test
    void refusesWhatIsNotBase58btc() {
        // 0, O, I, and l aren't in the alphabet.
        assertThatThrownBy(() -> Base58.decode("0OIl", 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base58.decode("2NEpo7TZRRrLZSi2U", 16)).isInstanceOf(IllegalArgumentException.class);
    }
}
