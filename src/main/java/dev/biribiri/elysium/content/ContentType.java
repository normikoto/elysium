package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a status's text is written in, named by the media types that glitch-soc, Pleroma, and Akkoma take.
 */
public enum ContentType {
    PLAIN("text/plain"),
    MARKDOWN("text/markdown");

    private final String value;

    ContentType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<ContentType> fromValue(String value) {
        return Arrays.stream(values()).filter(type -> type.value.equals(value)).findFirst();
    }
}
