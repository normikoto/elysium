package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.RemoteEmoji;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteEmojisTests {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsMastodonsEmojiTags() {
        var tag = json.readTree("""
                [{"id": "https://b.test/emojis/1", "type": "Emoji", "name": ":blob:",
                  "updated": "2026-01-02T03:04:05Z",
                  "icon": {"type": "Image", "mediaType": "image/png", "url": "https://b.test/blob.png"}},
                 {"type": "Hashtag", "name": "#blob"}]
                """);

        assertThat(RemoteEmojis.read(tag)).containsExactly(new RemoteEmoji("blob", URI.create("https://b.test/emojis/1"),
                URI.create("https://b.test/blob.png"), Instant.parse("2026-01-02T03:04:05Z")));
    }

    @Test
    void leavesOutThoseThatArentValidOrHaveNoHttpsImage() {
        var tag = json.readTree("""
                [{"type": "Emoji", "name": "blob", "icon": {"url": "https://b.test/a.png"}},
                 {"type": "Emoji", "name": ":a b:", "icon": {"url": "https://b.test/a.png"}},
                 {"type": "Emoji", "name": ":plain:", "icon": {"url": "http://b.test/a.png"}},
                 {"type": "Emoji", "name": ":none:"},
                 {"type": "Emoji", "name": ":dup:", "icon": {"url": "https://b.test/1.png"}},
                 {"type": "Emoji", "name": ":dup:", "icon": {"url": "https://b.test/2.png"}},
                 {"type": "Emoji", "name": ":link:", "icon": "https://b.test/link.png"}]
                """);

        assertThat(RemoteEmojis.read(tag)).extracting(RemoteEmoji::shortcode, RemoteEmoji::url).containsExactly(
                org.assertj.core.groups.Tuple.tuple("dup", URI.create("https://b.test/1.png")),
                org.assertj.core.groups.Tuple.tuple("link", URI.create("https://b.test/link.png")));
    }

    @Test
    void readsAtMostTheLimit() {
        var tags = new StringBuilder("[");
        for (var i = 0; i < RemoteEmojis.MAX + 5; i++) {
            tags.append(i == 0 ? "" : ",").append("""
                    {"type": "Emoji", "name": ":e%d:", "icon": {"url": "https://b.test/%d.png"}}""".formatted(i, i));
        }
        assertThat(RemoteEmojis.read(json.readTree(tags.append("]").toString()))).hasSize(RemoteEmojis.MAX);
    }
}
