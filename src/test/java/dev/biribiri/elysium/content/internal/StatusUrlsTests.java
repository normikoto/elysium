package dev.biribiri.elysium.content.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusUrlsTests {

    @Test
    void countsCodePointsInPlainText() {
        assertThat(StatusUrls.countCharacters("hello")).isEqualTo(5);
        assertThat(StatusUrls.countCharacters("😀😀")).isEqualTo(2);
    }

    @Test
    void countsEachUrlAs23Characters() {
        assertThat(StatusUrls.countCharacters("https://a.example")).isEqualTo(23);
        assertThat(StatusUrls.countCharacters("https://a.example/" + "x".repeat(500))).isEqualTo(23);
        assertThat(StatusUrls.countCharacters("see https://a.example and http://b.example")).isEqualTo(4 + 23 + 5 + 23);
    }

    @Test
    void countsPunctuationAfterAUrlSeparately() {
        assertThat(StatusUrls.countCharacters("https://a.example.")).isEqualTo(24);
    }

    @Test
    void doesNotCountOtherSchemesAsUrls() {
        assertThat(StatusUrls.countCharacters("ftp://a.example")).isEqualTo(15);
    }

    @Test
    void countsVeryLongUrlsAtTheirFullLength() {
        var prefix = "https://a.example/";
        var longest = prefix + "x".repeat(2048 - prefix.length());
        assertThat(StatusUrls.countCharacters(longest)).isEqualTo(23);
        assertThat(StatusUrls.countCharacters(longest + "x")).isEqualTo(2049);
    }
}
