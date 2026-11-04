package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CavageSignatureHeaderTests {

    @Test
    void readsQuotedAndUnquotedValues() {
        assertThat(CavageSignatureHeader.parse("keyId=\"https://a.example/k#1\",created=1402170695,signature=\"a+/=\""))
                .containsEntry("keyId", "https://a.example/k#1")
                .containsEntry("created", "1402170695")
                .containsEntry("signature", "a+/=");
    }

    @Test
    void allowsWhitespaceAroundSeparators() {
        assertThat(CavageSignatureHeader.parse(" a = \"1\" ,\tb=2 "))
                .containsEntry("a", "1")
                .containsEntry("b", "2");
    }

    @Test
    void stripsThePrefixOldClientsSend() {
        assertThat(CavageSignatureHeader.parse("Signature keyId=\"k\"")).containsEntry("keyId", "k");
    }

    @Test
    void decodesEscapesInQuotedValues() {
        assertThat(CavageSignatureHeader.parse("a=\"b\\\"c\\\\d\"")).containsEntry("a", "b\"c\\d");
    }

    @Test
    void keepsUnknownParametersForTheCallerToIgnore() {
        assertThat(CavageSignatureHeader.parse("keyId=\"k\",future=\"x\"")).containsEntry("future", "x");
    }

    @Test
    void keepsTheTextOfTimestamps() {
        assertThat(CavageSignatureHeader.parse("expires=1402170695.50")).containsEntry("expires", "1402170695.50");
    }

    @Test
    void rejectsDuplicateParameters() {
        assertMalformed("keyId=\"a\",keyId=\"b\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "keyId", "keyId=", "=\"a\"", "a=\"b", "a=\"b\\", "a=1,", "a=1 b=2", "a=1;b=2", ",a=1"})
    void rejectsMalformedHeaders(String header) {
        assertMalformed(header);
    }

    private static void assertMalformed(String header) {
        assertThatThrownBy(() -> CavageSignatureHeader.parse(header))
                .isInstanceOfSatisfying(SignatureVerificationException.class,
                        e -> assertThat(e.failure()).isEqualTo(Failure.MALFORMED));
    }
}
