package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TotpCodesTests {

    /**
     * The SHA-1 secret from RFC 6238's test vectors.
     */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfcTestVectors() {
        // RFC 6238 gives eight digits, and six are the last six of those.
        assertThat(TotpCodes.code(RFC_SECRET, TotpCodes.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(TotpCodes.code(RFC_SECRET, TotpCodes.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(TotpCodes.code(RFC_SECRET, TotpCodes.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsTheStepsEitherSideOfNowAndSaysWhichOne() {
        var now = Instant.ofEpochSecond(1111111109);
        var step = TotpCodes.step(now);

        assertThat(TotpCodes.matchingStep(RFC_SECRET, TotpCodes.code(RFC_SECRET, step), now)).hasValue(step);
        assertThat(TotpCodes.matchingStep(RFC_SECRET, TotpCodes.code(RFC_SECRET, step - 1), now)).hasValue(step - 1);
        assertThat(TotpCodes.matchingStep(RFC_SECRET, TotpCodes.code(RFC_SECRET, step + 1), now)).hasValue(step + 1);
    }

    @Test
    void refusesCodesFurtherAway() {
        var now = Instant.ofEpochSecond(1111111109);
        var step = TotpCodes.step(now);

        assertThat(TotpCodes.matchingStep(RFC_SECRET, TotpCodes.code(RFC_SECRET, step - 2), now)).isEmpty();
        assertThat(TotpCodes.matchingStep(RFC_SECRET, TotpCodes.code(RFC_SECRET, step + 2), now)).isEmpty();
    }

    @Test
    void refusesWhatIsNotSixDigits() {
        var now = Instant.ofEpochSecond(59);

        assertThat(TotpCodes.matchingStep(RFC_SECRET, "28708", now)).isEmpty();
        assertThat(TotpCodes.matchingStep(RFC_SECRET, "2870820", now)).isEmpty();
        assertThat(TotpCodes.matchingStep(RFC_SECRET, "２８７０８２", now)).isEmpty();
        assertThat(TotpCodes.matchingStep(RFC_SECRET, "", now)).isEmpty();
    }

    @Test
    void newSecretsAreRandom() {
        assertThat(TotpCodes.newSecret()).hasSize(20).isNotEqualTo(TotpCodes.newSecret());
    }

    @Test
    void buildsTheLinkThatAuthenticatorAppsRead() {
        var uri = TotpCodes.otpAuthUri(RFC_SECRET, "elysium.example", "alice@elysium.example:8443");

        assertThat(uri).isEqualTo("otpauth://totp/elysium.example:alice%40elysium.example%3A8443"
                + "?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=elysium.example&algorithm=SHA1&digits=6&period=30");
    }
}
