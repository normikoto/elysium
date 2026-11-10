package dev.biribiri.elysium.content;

import java.util.Optional;

/**
 * The point of an image to keep in view when a client crops it, from -1 to 1 across and from -1 (bottom) to 1 (top), as
 * on Mastodon.
 */
public record Focus(double x, double y) {

    public Focus {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("A focus has to be finite");
        }
        x = clamp(x);
        y = clamp(y);
    }

    /**
     * Reads Mastodon's {@code x,y}.
     *
     * @return empty for a value that's null or blank
     * @throws InvalidMediaAttachmentException if it isn't two numbers
     */
    public static Optional<Focus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        var parts = value.split(",", -1);
        try {
            if (parts.length == 2) {
                return Optional.of(new Focus(Double.parseDouble(parts[0].strip()), Double.parseDouble(parts[1].strip())));
            }
        } catch (IllegalArgumentException e) {
            // Refused below.
        }
        throw new InvalidMediaAttachmentException("Focus has to be two numbers from -1 to 1, as x,y");
    }

    private static double clamp(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
