package dev.biribiri.elysium.actor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param maxFields    how many fields a profile can have. Lowering this doesn't remove any, but a profile with more can't
 *                     save its fields again until it has few enough.
 * @param maxImageSize the largest avatar or header file that can be uploaded. Spring's limit on each file in
 *                     {@code application.properties} follows this, so that the server reads a file up to this size.
 */
@ConfigurationProperties("elysium.profiles")
public record ProfileProperties(
        @DefaultValue("" + ProfileProperties.DEFAULT_MAX_FIELDS) int maxFields,
        @DefaultValue("8MB") DataSize maxImageSize) {

    public static final int DEFAULT_MAX_FIELDS = 4;

    /**
     * Each field is a row on every profile page, and a link checked whenever the profile changes.
     */
    static final int MOST_FIELDS_ALLOWED = 50;

    public ProfileProperties {
        if (maxFields < 0 || maxFields > MOST_FIELDS_ALLOWED) {
            throw new IllegalArgumentException(
                    "elysium.profiles.max-fields must be from 0 to " + MOST_FIELDS_ALLOWED);
        }
        if (maxImageSize.toBytes() < 1) {
            throw new IllegalArgumentException("elysium.profiles.max-image-size must be at least 1B");
        }
    }
}
