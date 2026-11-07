package dev.biribiri.elysium.content;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxCharacters the most characters a new status can have, counting its text and content warning. Each URL in the
 *                      text counts as 23 characters.
 */
@ConfigurationProperties("elysium.statuses")
public record StatusProperties(@DefaultValue("" + StatusProperties.DEFAULT_MAX_CHARACTERS) int maxCharacters) {

    public static final int DEFAULT_MAX_CHARACTERS = 5000;

    public StatusProperties {
        if (maxCharacters < 1) {
            throw new IllegalArgumentException("elysium.statuses.max-characters must be at least 1");
        }
    }
}
