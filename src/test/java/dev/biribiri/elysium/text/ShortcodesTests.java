package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShortcodesTests {

    @Test
    void findsShortcodes() {
        assertThat(Shortcodes.find("Hi :blob_cat: and :Wave2:!")).containsExactly(
                new Shortcodes.Match(3, 13, "blob_cat"),
                new Shortcodes.Match(18, 25, "Wave2"));
    }

    @Test
    void ignoresShortcodesTouchingWordsColonsAndUrls() {
        assertThat(Shortcodes.find("a:blob: :blob:b 12:30:45 ::blob:: :x: https://a.example/:blob:")).isEmpty();
    }

    @Test
    void allowsShortcodesNextToPunctuationAndOtherEmoji() {
        assertThat(Shortcodes.find("(:blob:) :a_b::c_d:")).extracting(Shortcodes.Match::name).containsExactly("blob");
        assertThat(Shortcodes.find(":blob:, :cat:.")).extracting(Shortcodes.Match::name).containsExactly("blob", "cat");
    }

    @Test
    void namesAreDistinctAndCaseSensitiveAcrossTexts() {
        assertThat(Shortcodes.names(List.of(":blob: :Blob:", ":blob: :cat:"))).containsExactly("blob", "Blob", "cat");
    }

    @Test
    void keepsAtMostTheLimit() {
        var text = new StringBuilder();
        for (var i = 0; i < Shortcodes.MAX_PER_TEXT + 5; i++) {
            text.append(":e").append(i).append(": ");
        }
        assertThat(Shortcodes.names(List.of(text.toString()))).hasSize(Shortcodes.MAX_PER_TEXT);
    }

    @Test
    void parsesOneName() {
        assertThat(Shortcodes.parse(":blob:")).isEqualTo(Optional.of("blob"));
        assertThat(Shortcodes.parse("blob")).isEqualTo(Optional.of("blob"));
        assertThat(Shortcodes.parse(":b:")).isEmpty();
        assertThat(Shortcodes.parse("blob cat")).isEmpty();
        assertThat(Shortcodes.parse("ブロブ")).isEmpty();
        assertThat(Shortcodes.parse("a".repeat(Shortcodes.MAX_LENGTH + 1))).isEmpty();
        assertThat(Shortcodes.parse(null)).isEmpty();
    }
}
