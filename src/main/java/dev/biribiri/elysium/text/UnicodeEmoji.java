package dev.biribiri.elysium.text;

import java.util.regex.Pattern;

/**
 * Decides what counts as one Unicode emoji, for reactions, which are a single emoji rather than text.
 */
public final class UnicodeEmoji {

    /**
     * Longer than any emoji sequence Unicode defines, such as a family or a subdivision flag.
     */
    private static final int MAX_LENGTH = 64;

    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int EMOJI_PRESENTATION_SELECTOR = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;

    private UnicodeEmoji() {
    }

    /**
     * The fully qualified form of an emoji, as Unicode lists it and Pleroma stores it, so that one sent with or without
     * its variation selectors counts as the same. Misskey sends its default ❤ without one, and most clients send ❤️ with
     * it. The selector follows each emoji that isn't shown as one by default, unless a skin tone modifies it.
     *
     * @param emoji what {@link #isOne} accepts
     */
    public static String normalize(String emoji) {
        var codePoints = emoji.codePoints().filter(codePoint -> codePoint != EMOJI_PRESENTATION_SELECTOR).toArray();
        var normalized = new StringBuilder();
        for (var i = 0; i < codePoints.length; i++) {
            var codePoint = codePoints[i];
            normalized.appendCodePoint(codePoint);
            var modified = i + 1 < codePoints.length && Character.isEmojiModifier(codePoints[i + 1]);
            if (Character.isEmoji(codePoint) && !Character.isEmojiPresentation(codePoint)
                    && !Character.isEmojiModifier(codePoint) && !modified) {
                normalized.appendCodePoint(EMOJI_PRESENTATION_SELECTOR);
            }
        }
        return normalized.toString();
    }

    /**
     * One grapheme made only of emoji and the characters that join and modify them. Digits, {@code #} and {@code *} are
     * emoji to Unicode only in a keycap, so one has to be pictographic, a flag, or a keycap.
     */
    public static boolean isOne(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH || !GRAPHEME.matcher(value).matches()) {
            return false;
        }
        var pictured = false;
        for (var codePoint : value.codePoints().toArray()) {
            // A skin tone is shown as a swatch on its own, but is only meant to modify another emoji.
            if (Character.isEmojiModifier(codePoint)) {
                continue;
            }
            if (codePoint == COMBINING_KEYCAP || Character.isExtendedPictographic(codePoint)
                    || Character.isEmojiPresentation(codePoint)) {
                pictured = true;
            } else if (!Character.isEmoji(codePoint) && !Character.isEmojiComponent(codePoint)
                    && codePoint != ZERO_WIDTH_JOINER) {
                return false;
            }
        }
        return pictured;
    }
}
