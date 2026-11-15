package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.text.PlainText;
import dev.biribiri.elysium.text.Urls;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * A name and value that someone shows on their profile.
 *
 * @param value      as text. For an account on another server, it's what its HTML says.
 * @param verifiedAt when the page that the value links to was found to link back to the profile, or null
 * @param html       the value as another server sent it, sanitized, or null for a local account's, which is rendered from
 *                   its text
 */
public record ProfileField(String name, String value, Instant verifiedAt, String html) {

    /**
     * A local account's.
     */
    public ProfileField(String name, String value, Instant verifiedAt) {
        this(name, value, verifiedAt, null);
    }

    public String valueHtml() {
        return html != null ? html : PlainText.renderFieldValue(value);
    }

    /**
     * @return the value, if it's nothing but an HTTPS URL. Only these are checked for a link back, as Mastodon does, since a
     * link in the middle of other text isn't saying it's the same person.
     */
    public Optional<URI> link() {
        var links = Urls.find(value);
        if (links.size() != 1 || links.getFirst().start() != 0 || links.getFirst().end() != value.length()
                || !value.regionMatches(true, 0, "https://", 0, 8)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
