package dev.biribiri.elysium.content.internal;

import org.jsoup.Jsoup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class StatusLinks {

    private static final int MAX_URL_LENGTH = 2048;

    static final String ELIGIBLE = "s.visibility = 'public' AND NOT s.local_only AND s.reblog_of_id IS NULL"
            + " AND NOT s.sensitive AND s.spoiler_text = ''";

    private StatusLinks() {
    }

    static Set<String> from(String html) {
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
