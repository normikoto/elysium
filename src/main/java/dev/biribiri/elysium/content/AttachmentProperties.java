package dev.biribiri.elysium.content;

import dev.biribiri.elysium.media.MediaLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param maxImageSize         the largest image that can be uploaded, before it's re-encoded
 * @param maxVideoSize         the largest video that can be uploaded
 * @param maxAudioSize         the largest audio file that can be uploaded
 * @param unattachedRetention  how long an upload is kept for its status to be posted
 * @param redraftRetention     how long the files of a deleted status are kept, so that it can be posted again
 * @param maxUnattachedUploads how many uploads an account can have waiting to be posted, so that nobody can fill the disk
 *                             with files that never appear anywhere
 */
@ConfigurationProperties("elysium.attachments")
public record AttachmentProperties(
        @DefaultValue("16MB") DataSize maxImageSize,
        @DefaultValue("100MB") DataSize maxVideoSize,
        @DefaultValue("40MB") DataSize maxAudioSize,
        @DefaultValue("P1D") Duration unattachedRetention,
        @DefaultValue("PT1H") Duration redraftRetention,
        @DefaultValue("20") int maxUnattachedUploads) {

    public AttachmentProperties {
        if (maxImageSize.toBytes() < 1 || maxVideoSize.toBytes() < 1 || maxAudioSize.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.attachments size limits must be at least 1 byte");
        }
        if (unattachedRetention.isNegative() || redraftRetention.isNegative()) {
            throw new IllegalArgumentException("elysium.attachments retention can't be negative");
        }
        if (maxUnattachedUploads < 1) {
            throw new IllegalArgumentException("elysium.attachments.max-unattached-uploads must be at least 1");
        }
    }

    public MediaLimits limits() {
        return new MediaLimits(maxImageSize, maxVideoSize, maxAudioSize);
    }

    /**
     * The largest file of any kind.
     */
    public DataSize largest() {
        return limits().largest();
    }
}
