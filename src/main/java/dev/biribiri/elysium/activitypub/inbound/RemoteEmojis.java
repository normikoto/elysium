package dev.biribiri.elysium.activitypub.inbound;

import tools.jackson.databind.JsonNode;
import dev.biribiri.elysium.content.RemoteEmoji;
import dev.biribiri.elysium.text.Shortcodes;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Reads the {@code Emoji} items of a {@code tag}, as Mastodon sends them: a {@code name} of {@code :shortcode:}, and an
 * {@code icon} that's an {@code Image} with a {@code url}.
 */
final class RemoteEmojis {

    /**
     * Each is stored, and its image downloaded, so one object can't name any number of them.
     */
    static final int MAX = 100;

    private RemoteEmojis() {
    }

    /**
     * Only HTTPS images are kept, since clients load ones that aren't downloaded from where they are, on pages that are
     * served over HTTPS. The first of each shortcode is taken.
     */
    static List<RemoteEmoji> read(JsonNode tag) {
        var emojis = new ArrayList<RemoteEmoji>();
        var seen = new HashSet<String>();
        for (var candidate : tag.isArray() ? tag.valueStream().toList() : List.of(tag)) {
            if (emojis.size() == MAX) {
                break;
            }
            if (!candidate.isObject() || !ActivityJson.types(candidate.path("type")).contains("Emoji")) {
                continue;
            }
            var name = ActivityJson.text(candidate.path("name"));
            if (name == null || !name.startsWith(":") || !name.endsWith(":")) {
                continue;
            }
            var shortcode = Shortcodes.parse(name);
            var url = iconUrl(candidate.path("icon"));
            if (shortcode.isEmpty() || url.isEmpty() || !seen.add(shortcode.get())) {
                continue;
            }
            var id = Optional.ofNullable(ActivityJson.text(candidate.path("id")))
                    .flatMap(ActivityObject::parseUri)
                    .filter(uri -> uri.getHost() != null && InboundActivity.isStorableId(uri.toString()))
                    .orElse(null);
            emojis.add(new RemoteEmoji(shortcode.get(), id, url.get(), RemoteNote.timestamp(candidate.path("updated"))));
        }
        return emojis;
    }

    /**
     * Reads an {@code icon} or {@code image}, which can be a URL, an {@code Image} whose {@code url} is one or a
     * {@code Link}, or an array of those, of which the first is taken.
     *
     * @return the URL, if it's HTTPS
     */
    static Optional<URI> iconUrl(JsonNode icon) {
        var image = icon.isArray() ? icon.path(0) : icon;
        var url = image.isObject() ? image.path("url") : image;
        var href = url.isObject() ? ActivityJson.text(url.path("href")) : ActivityJson.text(url);
        return Optional.ofNullable(href)
                .flatMap(ActivityObject::parseUri)
                .filter(uri -> "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null)
                .filter(uri -> InboundActivity.isStorableId(uri.toString()));
    }
}
