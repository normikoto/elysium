package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;

public enum Visibility {
    PUBLIC, UNLISTED, PRIVATE, DIRECT;

    /**
     * The name used in the API and the database.
     */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether anyone can see a status with this visibility, which also decides whether it can be quoted by others.
     */
    public boolean isOpen() {
        return this == PUBLIC || this == UNLISTED;
    }

    /**
     * @throws InvalidStatusException if there's no visibility with this name
     */
    public static Visibility fromValue(String value) {
        return Arrays.stream(values())
                .filter(visibility -> visibility.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new InvalidStatusException("Visibility is not recognized"));
    }
}
