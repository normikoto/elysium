package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A status from another server, as it's been checked and read from there.
 *
 * @param url          the page that shows it, or null if none was given
 * @param content      HTML, which hasn't been sanitized yet
 * @param spoilerText  HTML, which is reduced to plain text, as clients show it
 * @param language     a language code, or null if not given or not valid
 * @param inReplyToUri the parent's URI, or null if it isn't a reply
 * @param editedAt     when its server says it was last edited, or null if it hasn't been
 */
public record RemoteStatus(
        URI uri,
        URI url,
        UUID authorId,
        String content,
        String spoilerText,
        String language,
        Visibility visibility,
        boolean sensitive,
        URI inReplyToUri,
        Instant publishedAt,
        Instant editedAt
) {

    /**
     * A status that hasn't been edited.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, null);
    }
}
