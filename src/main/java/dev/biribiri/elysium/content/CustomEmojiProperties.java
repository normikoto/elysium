package dev.biribiri.elysium.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxImageSize the largest image an admin can upload for an emoji, or that's downloaded for another server's, before
 *                     it's re-encoded. The default is Mastodon's, which other servers are used to sending within, so a
 *                     smaller one leaves some of their emoji as links
 */
@ConfigurationProperties("elysium.custom-emojis")
public record CustomEmojiProperties(@DefaultValue("256KB") DataSize maxImageSize) {

    public CustomEmojiProperties {
        if (maxImageSize.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.custom-emojis.max-image-size must be at least 1 byte");
        }
    }
}
