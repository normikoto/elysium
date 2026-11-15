package dev.biribiri.elysium.text;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.regex.Pattern;

/**
 * Finds custom emoji such as {@code :blobcat:} in text. A status's emoji, a profile's, and the web pages that show them
 * all use this, so that what's shown as an image is what was sent to other servers as one.
 */
public final class Shortcodes {

    /**
     * Longer names aren't emoji, so that a status can't name thousands of characters' worth of emoji to look up.
     */
    public static final int MAX_LENGTH = 100;

    /**
     * How many emoji a status or a profile has. Mastodon has no limit, and real posts use far fewer.
     */
    public static final int MAX_PER_TEXT = 100;

    /**
     * Mastodon's pattern, so that text means the same here as there: a shortcode can't touch a letter, a digit, or
     * another colon, which keeps times such as {@code 12:30:45} and runs such as {@code ::a::} from counting.
     */
    private static final Pattern SHORTCODE =
            Pattern.compile("(?<![\\p{L}\\p{N}:]):([A-Za-z0-9_]{2,}+):(?![\\p{L}\\p{N}:])");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{2," + MAX_LENGTH + "}");

    private Shortcodes() {
    }

    /**
     * A shortcode's position in the text, from {@code start} up to but not including {@code end}, which covers the colons.
     *
     * @param name the name without colons
     */
    public record Match(int start, int end, String name) {
    }

    /**
     * Shortcodes inside a URL, such as {@code https://a.example/:x:}, are part of the URL, and aren't returned.
     */
    public static List<Match> find(String text) {
        var urls = Urls.find(text);
        return SHORTCODE.matcher(text).results()
                .filter(match -> match.group(1).length() <= MAX_LENGTH)
                .filter(match -> urls.stream().noneMatch(url -> match.start() < url.end() && url.start() < match.end()))
                .map(match -> new Match(match.start(), match.end(), match.group(1)))
                .toList();
    }

    /**
     * The distinct shortcodes in all the texts, in the order they first appear, up to {@value MAX_PER_TEXT}. Shortcodes
     * are matched with case, as Mastodon matches them.
     */
    public static SequencedSet<String> names(Collection<String> texts) {
        var names = new LinkedHashSet<String>();
        for (var text : texts) {
            if (text == null) {
                continue;
            }
            for (var match : find(text)) {
                if (names.size() == MAX_PER_TEXT) {
                    return names;
                }
                names.add(match.name());
            }
        }
        return names;
    }

    /**
     * Reads a name as another server or a request gives it, with or without its colons.
     *
     * @return the name without colons, or empty if it isn't one
     */
    public static Optional<String> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        var bare = name.length() > 2 && name.startsWith(":") && name.endsWith(":")
                ? name.substring(1, name.length() - 1) : name;
        return NAME.matcher(bare).matches() ? Optional.of(bare) : Optional.empty();
    }
}
