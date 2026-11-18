package dev.biribiri.elysium.actor;

import dev.biribiri.elysium.text.PlainText;
import dev.biribiri.elysium.text.RemoteHtml;
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
        if (html == null) {
            return PlainText.renderFieldValue(value);
        }
        return verifiedAt == null ? html : link().map(RemoteHtml::verifiedFieldLink).orElse(html);
    }

    /**
     * @return the destination if the field is a standalone HTTPS URL or a standalone remote anchor to one. A link in the
     * middle of other text isn't saying it's the same person.
     */
    public Optional<URI> link() {
        if (html != null) {
            return RemoteHtml.fieldLinkValue(html).flatMap(ProfileField::httpsUrl);
        }
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

    private static Optional<URI> httpsUrl(String value) {
        try {
            var uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? Optional.of(uri)
                    : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
