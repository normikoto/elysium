package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Base32Tests {

    /**
     * RFC 4648's test vectors, without their padding.
     */
    @ParameterizedTest
    @CsvSource(value = {
            "'', ''",
            "f, MY",
            "fo, MZXQ",
            "foo, MZXW6",
            "foob, MZXW6YQ",
            "fooba, MZXW6YTB",
            "foobar, MZXW6YTBOI",
    })
    void matchesTheRfcTestVectors(String input, String encoded) {
        assertThat(Base32.encode(input.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
    }

    @ParameterizedTest
    @CsvSource({"-1, 74", "0, AA"})
    void encodesEveryBitOfAByte(byte value, String encoded) {
        assertThat(Base32.encode(new byte[]{value})).isEqualTo(encoded);
    }
}
