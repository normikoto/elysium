package dev.biribiri.elysium.push.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushMessagesTests {

    @Test
    void cutsLongTextWithoutSplittingCharacters() {
        var text = "🍉".repeat(PushMessages.MAX_BODY + 10);

        var truncated = PushMessages.truncate(text);

        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(PushMessages.MAX_BODY);
        assertThat(truncated).endsWith("🍉…");
    }

    @Test
    void leavesShortTextAlone() {
        assertThat(PushMessages.truncate("hello")).isEqualTo("hello");
        var exact = "a".repeat(PushMessages.MAX_BODY);
        assertThat(PushMessages.truncate(exact)).isEqualTo(exact);
    }
}
