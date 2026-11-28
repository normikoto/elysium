package dev.biribiri.elysium.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxCharacters   the most characters a new status can have, counting its text and content warning. Each URL in
 *                        the text counts as 23 characters.
 * @param remoteRetention how long other servers' statuses are kept after they were stored here, unless something here
 *                        needs them, as {@link RemoteStatusExpiry} says, or null to keep them for good
 */
@ConfigurationProperties("elysium.statuses")
public record StatusProperties(
        @DefaultValue("" + StatusProperties.DEFAULT_MAX_CHARACTERS) int maxCharacters,
        Duration remoteRetention) {

    public static final int DEFAULT_MAX_CHARACTERS = 5000;

    /**
     * So that a mistyped unit, such as minutes for days, can't remove what was just received.
     */
    static final Duration MIN_REMOTE_RETENTION = Duration.ofDays(1);

    public StatusProperties {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("elysium.statuses.max-characters must be at least 1");
        }
        if (remoteRetention != null && remoteRetention.compareTo(MIN_REMOTE_RETENTION) < 0) {
            throw new IllegalArgumentException("elysium.statuses.remote-retention must be at least 1d");
        }
    }
}
