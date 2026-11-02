package dev.biribiri.elysium.content.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds the URLs in a status's text. Rendering and the length limit both use this, so that whatever becomes a link is
 * also what gets counted as one.
 */
public final class StatusUrls {

    /**
     * What each URL counts as toward the length limit, regardless of its length. This matches Mastodon, and clients
     * assume this value.
     */
    public static final int CHARACTERS_RESERVED_PER_URL = 23;

    /**
     * Longer URLs count at their full length, so that the limit still bounds how much text a status can store.
     */
    private static final int MAX_RESERVED_URL_LENGTH = 2048;

    private static final Pattern URL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING_PUNCTUATION = ".,;:!?'*_~";

    private StatusUrls() {
    }

    /**
     * A URL's position in the text, from {@code start} up to but not including {@code end}.
     */
    record Match(int start, int end) {
    }

    static List<Match> find(String text) {
        var matches = new ArrayList<Match>();
        var matcher = URL.matcher(text);
        while (matcher.find()) {
            var url = trim(matcher.group());
            if (!url.endsWith("://")) {
                matches.add(new Match(matcher.start(), matcher.start() + url.length()));
            }
        }
        return matches;
    }

    /**
     * Counts code points, with each URL counted as {@value CHARACTERS_RESERVED_PER_URL} characters.
     */
    public static int countCharacters(String text) {
        var count = text.codePointCount(0, text.length());
        for (var match : find(text)) {
            var length = text.codePointCount(match.start(), match.end());
            if (length <= MAX_RESERVED_URL_LENGTH) {
                count += CHARACTERS_RESERVED_PER_URL - length;
            }
        }
        return count;
    }

    /**
     * Punctuation after a URL almost always belongs to the sentence around it. A closing bracket is kept only if the
     * URL also contains its opening bracket, as in Wikipedia links.
     */
    private static String trim(String url) {
        var end = url.length();
        while (end > 0) {
            var last = url.charAt(end - 1);
            var unmatchedBracket = (last == ')' && unbalanced(url, end, '(', ')'))
                    || (last == ']' && unbalanced(url, end, '[', ']'));
            if (TRAILING_PUNCTUATION.indexOf(last) < 0 && !unmatchedBracket) {
                break;
            }
            end--;
        }
        return url.substring(0, end);
    }

    private static boolean unbalanced(String url, int end, char open, char close) {
        var opened = url.substring(0, end).chars().filter(c -> c == open).count();
        var closed = url.substring(0, end).chars().filter(c -> c == close).count();
        return closed > opened;
    }
}
