package dev.biribiri.elysium.content;

import dev.biribiri.elysium.media.MediaLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param remoteCacheEnabled   whether other servers' attachments are downloaded and served from here, which keeps
 *                             people's browsers from fetching them from those servers
 * @param remoteCacheRetention how long a copy of another server's attachment is kept, after which it's linked again
 * @param remoteDownloadBudget how much each other server's attachments can add up to in an hour, beyond which they're
 *                             only linked
 */
@ConfigurationProperties("elysium.attachments")
public record AttachmentProperties(
        @DefaultValue("16MB") DataSize maxImageSize,
        @DefaultValue("100MB") DataSize maxVideoSize,
        @DefaultValue("40MB") DataSize maxAudioSize,
        @DefaultValue("P1D") Duration unattachedRetention,
        @DefaultValue("PT1H") Duration redraftRetention,
        @DefaultValue("20") int maxUnattachedUploads,
        @DefaultValue("true") boolean remoteCacheEnabled,
        @DefaultValue("P30D") Duration remoteCacheRetention,
        @DefaultValue("2GB") DataSize remoteDownloadBudget) {

    @ConstructorBinding
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
        if (remoteCacheRetention.isNegative() || remoteCacheRetention.isZero()) {
            throw new IllegalArgumentException("elysium.attachments.remote-cache-retention must be positive");
        }
        if (remoteDownloadBudget.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.attachments.remote-download-budget must be at least 1 byte");
        }
    }

    /**
     * With the defaults for other servers' attachments.
     */
    public AttachmentProperties(DataSize maxImageSize, DataSize maxVideoSize, DataSize maxAudioSize,
                                Duration unattachedRetention, Duration redraftRetention, int maxUnattachedUploads) {
        this(maxImageSize, maxVideoSize, maxAudioSize, unattachedRetention, redraftRetention, maxUnattachedUploads, true,
                Duration.ofDays(30), DataSize.ofGigabytes(2));
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
