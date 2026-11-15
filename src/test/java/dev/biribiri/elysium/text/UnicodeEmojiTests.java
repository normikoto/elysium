package dev.biribiri.elysium.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UnicodeEmojiTests {

    @ParameterizedTest
    @ValueSource(strings = {"👍", "❤", "❤️", "👍🏽", "👩‍👩‍👧", "🇯🇵", "1️⃣", "🏴󠁧󠁢󠁳󠁣󠁴󠁿", "☺️"})
    void acceptsOneEmoji(String value) {
        assertThat(UnicodeEmoji.isOne(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1", "#", "👍👍", "👍 ", "hi", ":blob:", "‍", "🏽"})
    void refusesAnythingElse(String value) {
        assertThat(UnicodeEmoji.isOne(value)).isFalse();
    }
}
