package dev.biribiri.elysium.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds mentions such as {@code @alice} and {@code @alice@example.com} in text. Rendering and a status's length limit both
 * use this, as they use {@link Urls}, so that what's counted as a mention is also what could become one.
 */
public final class Mentions {

    /**
     * Mastodon's pattern, so that a status means the same here as there. Nothing that's part of a word, a URL path, or a
     * query string can start one, which keeps {@code alice@example.com} an email address.
     */
    private static final Pattern MENTION = Pattern.compile(
            "(?<![=/\\p{L}\\p{M}\\p{N}_])@([a-z0-9_]+(?:[a-z0-9_.-]+[a-z0-9_]+)?)(?:@([\\p{L}\\p{M}\\p{N}_.-]+[\\p{L}\\p{M}\\p{N}_]))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * How many characters the domains in a status's mentions can save in total, for the same reason as
     * {@link Urls#MAX_URL_SAVINGS}. That's enough for many more mentions than anyone writes.
     */
    public static final int MAX_DOMAIN_SAVINGS = 4096;

    private Mentions() {
    }

    /**
     * A mention's position in the text, from {@code start} up to but not including {@code end}.
     *
     * @param domain the domain as written, or null if there was none
     */
    public record Match(int start, int end, String username, String domain) {
    }

    /**
     * Mentions inside a URL, such as {@code https://example.com/@alice}, are part of the URL, and aren't returned.
     */
    public static List<Match> find(String text) {
        var urls = Urls.find(text);
        var matches = new ArrayList<Match>();
        var matcher = MENTION.matcher(text);
        while (matcher.find()) {
            var start = matcher.start();
            var end = matcher.end();
            if (urls.stream().noneMatch(url -> start < url.end() && url.start() < end)) {
                matches.add(new Match(start, end, matcher.group(1), matcher.group(2)));
            }
        }
        return matches;
    }

    /**
     * How many characters the domains of the mentions take up, including their {@code @}. Mastodon doesn't count them toward
     * a status's length, and clients assume that, up to {@value MAX_DOMAIN_SAVINGS} characters.
     */
    static int domainCharacters(String text) {
        return Math.min(MAX_DOMAIN_SAVINGS, find(text).stream()
                .filter(match -> match.domain() != null)
                .mapToInt(match -> match.domain().codePointCount(0, match.domain().length()) + 1)
                .sum());
    }
}
