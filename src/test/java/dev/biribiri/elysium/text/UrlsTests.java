package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class UrlsTests {

    @Test
    void countsCodePointsInPlainText() {
        assertThat(Urls.countCharacters("hello")).isEqualTo(5);
        assertThat(Urls.countCharacters("😀😀")).isEqualTo(2);
    }

    @Test
    void countsEachUrlAs23Characters() {
        assertThat(Urls.countCharacters("https://a.example")).isEqualTo(23);
        assertThat(Urls.countCharacters("https://a.example/" + "x".repeat(500))).isEqualTo(23);
        assertThat(Urls.countCharacters("see https://a.example and http://b.example")).isEqualTo(4 + 23 + 5 + 23);
    }

    @Test
    void countsPunctuationAfterAUrlSeparately() {
        assertThat(Urls.countCharacters("https://a.example.")).isEqualTo(24);
    }

    @Test
    void textAfterAUrlIsNotPartOfIt() {
        for (var after : new String[]{"　次の文章", " next", "を見て", "。", "次", "한국어", "ไทย", "😀"}) {
            var text = "https://a.example" + after;
            assertThat(Urls.countCharacters(text))
                    .isEqualTo(23 + after.codePointCount(0, after.length()));
        }
    }

    @Test
    void countsLinksWithNonAsciiLettersAsOneUrl() {
        assertThat(Urls.countCharacters("https://ru.wikipedia.org/wiki/Москва")).isEqualTo(23);
        assertThat(Urls.countCharacters("https://a.example/café")).isEqualTo(23);
    }

    @Test
    void handlesHugeInputQuickly() {
        var size = 2_000_000;
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            for (var filler : new String[]{")", "]", ".", "()", "x", "я"}) {
                Urls.countCharacters("https://a.example/" + filler.repeat(size));
            }
        });
    }

    @Test
    void doesNotCountOtherSchemesAsUrls() {
        assertThat(Urls.countCharacters("ftp://a.example")).isEqualTo(15);
    }

    @Test
    void countsVeryLongUrlsAtTheirFullLength() {
        var prefix = "https://a.example/";
        var longest = prefix + "x".repeat(2048 - prefix.length());
        assertThat(Urls.countCharacters(longest)).isEqualTo(23);
        assertThat(Urls.countCharacters(longest + "x")).isEqualTo(2049);
    }

    @Test
    void limitsHowManyCharactersUrlsCanSave() {
        var prefix = "https://a.example/";
        var url = prefix + "x".repeat(2048 - prefix.length());
        assertThat(Urls.countCharacters(url + " " + url)).isEqualTo(23 + 1 + 23);

        var text = String.join(" ", url, url, url, url);
        assertThat(Urls.countCharacters(text)).isEqualTo(text.length() - Urls.MAX_URL_SAVINGS);
    }
}
