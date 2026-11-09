package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HashtagsTests {

    @Test
    void findsHashtags() {
        assertThat(Hashtags.find("#cats and #Dogs!")).containsExactly(
                new Hashtags.Match(0, 5, "cats"),
                new Hashtags.Match(10, 15, "Dogs"));
    }

    @Test
    void needsALetter() {
        assertThat(Hashtags.find("#1 #123 # #_ #2024年")).containsExactly(new Hashtags.Match(13, 19, "2024年"));
    }

    @Test
    void ignoresHashesInsideWordsAndUrls() {
        assertThat(Hashtags.find("C#sharp a=#b (x)#y https://a.example/page#section https://a.example/#top")).isEmpty();
    }

    @Test
    void allowsUnicodeAndInnerSeparators() {
        assertThat(Hashtags.find("#日本語 #l·l #café_au_lait #nom·")).extracting(Hashtags.Match::name)
                .containsExactly("日本語", "l·l", "café_au_lait", "nom");
    }

    @Test
    void ignoresNamesThatAreTooLong() {
        assertThat(Hashtags.find("#" + "a".repeat(Hashtags.MAX_NAME_LENGTH))).hasSize(1);
        assertThat(Hashtags.find("#" + "a".repeat(Hashtags.MAX_NAME_LENGTH + 1))).isEmpty();
    }

    @Test
    void namesAreNormalizedAndDistinct() {
        assertThat(Hashtags.names("#Cats #cats #ＣＡＴＳ #dogs")).containsExactly("cats", "dogs");
    }

    @Test
    void keepsAtMostTheLimit() {
        var text = new StringBuilder();
        for (var i = 0; i < Hashtags.MAX_PER_STATUS + 5; i++) {
            text.append("#t").append(i).append(' ');
        }
        assertThat(Hashtags.names(text.toString())).hasSize(Hashtags.MAX_PER_STATUS);
    }

    @Test
    void parsesOneName() {
        assertThat(Hashtags.parse("#Cats")).isEqualTo(Optional.of("cats"));
        assertThat(Hashtags.parse("Café")).isEqualTo(Optional.of("café"));
        assertThat(Hashtags.parse("123")).isEmpty();
        assertThat(Hashtags.parse("a b")).isEmpty();
        assertThat(Hashtags.parse("")).isEmpty();
        assertThat(Hashtags.parse(null)).isEmpty();
    }

    @Test
    void encodesTheUrl() {
        assertThat(Hashtags.url(URI.create("https://elysium.example"), "café"))
                .isEqualTo(URI.create("https://elysium.example/tags/caf%C3%A9"));
    }
}
