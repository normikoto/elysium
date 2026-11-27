package dev.biribiri.elysium.text;

import org.jsoup.Jsoup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the links a status shares, in the HTML that {@link PlainText}, {@link Markdown}, and {@link RemoteHtml} make.
 * Mentions, hashtags, and the {@code RE:} line of a quote are links too, but they aren't shared, so the classes those
 * give their markup decide what's left out.
 */
public final class SharedLinks {

    /**
     * Longer URLs aren't counted, so that one status can't fill the index with a URL thousands of characters long.
     */
    private static final int MAX_URL_LENGTH = 2048;

    private SharedLinks() {
    }

    /**
     * @return each link once, in order, written the same way however the HTML wrote it, so that one page counts as
     * one link
     */
    public static Set<String> in(String html) {
        if (!html.contains("href=")) {
            return Set.of();
        }
        var body = Jsoup.parseBodyFragment(html).body();
        body.select(".quote-inline, .h-card").remove();
        var urls = new LinkedHashSet<String>();
        for (var anchor : body.select("a[href]:not(.mention):not(.hashtag)")) {
            var url = normalized(anchor.attr("href"));
            if (url != null) {
                urls.add(url);
            }
        }
        return urls;
    }

    private static String normalized(String href) {
        if (href.length() > MAX_URL_LENGTH) {
            return null;
        }
        try {
            var uri = new URI(href);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535) {
                return null;
            }
            var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            var defaultPort = switch (scheme) {
                case "http" -> 80;
                case "https" -> 443;
                default -> -1;
            };
            if (defaultPort == -1) {
                return null;
            }
            var port = uri.getPort() == -1 || uri.getPort() == defaultPort ? "" : ":" + uri.getPort();
            var query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            var url = new URI(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + port + uri.getRawPath() + query)
                    .toASCIIString();
            return url.length() <= MAX_URL_LENGTH ? url : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
