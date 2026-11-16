package dev.biribiri.elysium.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxImageSize the largest image an admin can upload for an emoji, or that's downloaded for another server's, before
 *                     it's re-encoded. The default is Mastodon's, which other servers are used to sending within, so a
 *                     smaller one leaves some of their emoji as links
 * @param maxPackSize  the largest pack that can be uploaded on the admin page. The command line has no limit, since it's
 *                     for packs too large to import while a request waits
 * @param maxPackFiles the most emoji a pack uploaded on the admin page can have, since each is re-encoded while the
 *                     request waits
 */
@ConfigurationProperties("elysium.custom-emojis")
public record CustomEmojiProperties(
        @DefaultValue("256KB") DataSize maxImageSize,
        @DefaultValue("50MB") DataSize maxPackSize,
        @DefaultValue("1000") int maxPackFiles) {

    public CustomEmojiProperties {
        if (maxImageSize.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.custom-emojis.max-image-size must be at least 1 byte");
        }
        if (maxPackSize.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.custom-emojis.max-pack-size must be at least 1 byte");
        }
        if (maxPackFiles < 1) {
            throw new IllegalArgumentException("elysium.custom-emojis.max-pack-files must be at least 1");
        }
    }
}
