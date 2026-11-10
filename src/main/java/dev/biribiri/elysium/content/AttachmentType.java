package dev.biribiri.elysium.content;

import java.util.Locale;

/**
 * What a client is told an attachment is. {@code UNKNOWN} is a file that can't be shown here, only linked to, such as a
 * remote one that isn't a format that's accepted.
 */
public enum AttachmentType {
    IMAGE, VIDEO, AUDIO, UNKNOWN;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AttachmentType fromValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
