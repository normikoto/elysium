package dev.biribiri.elysium.text;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmojiHtmlTests {

    private static final Map<String, String> EMOJIS = Map.of("blob", "https://a.example/media/blob.webp");

    @Test
    void replacesKnownShortcodesInText() {
        assertThat(EmojiHtml.render("<p>Hi :blob: :other:</p>", EMOJIS)).isEqualTo(
                "<p>Hi <img class=\"custom-emoji\" src=\"https://a.example/media/blob.webp\" alt=\":blob:\" "
                        + "title=\":blob:\" draggable=\"false\"> :other:</p>");
    }

    @Test
    void leavesAttributesAlone() {
        var html = "<p><a href=\"https://b.example/:blob:\" title=\":blob:\">link</a></p>";
        assertThat(EmojiHtml.render(html, EMOJIS)).isEqualTo(html);
    }

    @Test
    void escapesPlainText() {
        assertThat(EmojiHtml.renderText("<b> :blob:", EMOJIS)).startsWith("&lt;b&gt; <img ");
        assertThat(EmojiHtml.renderText("<b>", Map.of())).isEqualTo("&lt;b&gt;");
    }
}
