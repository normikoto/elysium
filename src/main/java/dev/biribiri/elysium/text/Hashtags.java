package dev.biribiri.elysium.text;

import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.regex.Pattern;

/**
 * Finds hashtags such as {@code #cats} in text, and decides what counts as the same tag. Rendering, storing a status's
 * tags, and looking a tag up all use this, so that a tag that's linked is also one that can be found.
 */
public final class Hashtags {

    /**
     * Longer names aren't tags. Without a limit, one status could fill the index with a name thousands of characters long.
     */
    public static final int MAX_NAME_LENGTH = 100;

    /**
     * How many tags a status keeps. Mastodon has no limit, but the rest are almost always spam.
     */
    public static final int MAX_PER_STATUS = 100;

    /**
     * Mastodon's pattern, so that a status means the same here as there: nothing that's part of a word, a URL, or an
     * {@code a=b} pair can start one. A middle dot and a zero-width non-joiner can appear inside a name, as Catalan and
     * Persian need, but not at either end. Possessive, so that a long run of letters can't backtrack.
     */
    private static final Pattern HASHTAG = Pattern.compile(
            "(?<![=/)\\p{L}\\p{M}\\p{N}_])#([\\p{L}\\p{M}\\p{N}_][\\p{L}\\p{M}\\p{N}_·\\u200c]*+)");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N}_](?:[\\p{L}\\p{M}\\p{N}_·\\u200c]*[\\p{L}\\p{M}\\p{N}_])?");
    private static final Pattern LETTER = Pattern.compile("[\\p{L}\\p{M}]");

    private Hashtags() {
    }

    /**
     * A hashtag's position in the text, from {@code start} up to but not including {@code end}.
     *
     * @param name the name as written, without the {@code #}
     */
    public record Match(int start, int end, String name) {
    }

    /**
     * Hashtags inside a URL, such as {@code https://example.com/page#section}, are part of the URL, and aren't returned.
     */
    public static List<Match> find(String text) {
        var urls = Urls.find(text);
        var matches = new ArrayList<Match>();
        var matcher = HASHTAG.matcher(text);
        while (matcher.find()) {
            var name = trim(matcher.group(1));
            var start = matcher.start();
            var end = matcher.start(1) + name.length();
            if (isName(name) && urls.stream().noneMatch(url -> start < url.end() && url.start() < end)) {
                matches.add(new Match(start, end, name));
            }
        }
        return matches;
    }

    /**
     * The distinct tags in text, normalized, in the order they first appear, up to {@value MAX_PER_STATUS}.
     */
    public static SequencedSet<String> names(String text) {
        var names = new LinkedHashSet<String>();
        for (var match : find(text)) {
            if (names.size() == MAX_PER_STATUS) {
                break;
            }
            parse(match.name()).ifPresent(names::add);
        }
        return names;
    }

    /**
     * Reads one tag's name, as given in a URL, a search, or another server's {@code Hashtag}, with or without a leading
     * {@code #}.
     *
     * @return the normalized name, or empty if it isn't one
     */
    public static Optional<String> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        var bare = name.startsWith("#") ? name.substring(1) : name;
        var normalized = normalize(bare);
        return isName(normalized) ? Optional.of(normalized) : Optional.empty();
    }

    /**
     * Tags match without regard to case or to how a character was composed, so {@code #Café} and {@code #CAFÉ} are one
     * tag. This is what's stored and looked up, and what the tag's URL is built from.
     */
    public static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * Where this server shows a tag's public statuses. Other servers are sent it as the tag's {@code href}, so it can't
     * change.
     *
     * @param name a normalized name
     */
    public static URI url(URI baseUrl, String name) {
        return baseUrl.resolve("/tags/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8));
    }

    private static boolean isName(String name) {
        return name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH
                && NAME.matcher(name).matches()
                && LETTER.matcher(name).find();
    }

    private static String trim(String name) {
        var end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '·' || name.charAt(end - 1) == '‌')) {
            end--;
        }
        return name.substring(0, end);
    }
}
