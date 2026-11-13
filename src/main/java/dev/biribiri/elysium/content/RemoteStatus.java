package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * @param mentions     the stored accounts it mentions, each with whether that's silent, as for a local account it was sent
 *                     to without being tagged. These can see it even when it's followers-only or direct, so they have to
 *                     have been checked against who it was addressed to
 * @param tags         the names of its hashtags as its server gave them, which are checked and normalized
 *                     when it's stored
 * @param attachments  its attachments, in order
 * @param quote        what it quotes, or null if it doesn't
 * @param quotePolicy  who its server says can quote it, or null if it doesn't say
 * @param poll         its poll, or null if it has none
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
        Instant editedAt,
        Map<UUID, Boolean> mentions,
        Collection<String> tags,
        List<RemoteAttachment> attachments,
        RemoteQuote quote,
        QuoteApproval quotePolicy,
        RemotePoll poll
) {

    public RemoteStatus {
        mentions = Map.copyOf(mentions);
        tags = List.copyOf(tags);
        attachments = List.copyOf(attachments);
    }

    /**
     * A status with no poll.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt, Instant editedAt, Map<UUID, Boolean> mentions,
            Collection<String> tags, List<RemoteAttachment> attachments, RemoteQuote quote, QuoteApproval quotePolicy) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, editedAt,
                mentions, tags, attachments, quote, quotePolicy, null);
    }

    /**
     * A status that quotes nothing.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt, Instant editedAt, Map<UUID, Boolean> mentions,
            Collection<String> tags, List<RemoteAttachment> attachments) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, editedAt,
                mentions, tags, attachments, null, null);
    }

    /**
     * A status with no attachments.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt, Instant editedAt, Map<UUID, Boolean> mentions,
            Collection<String> tags) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, editedAt,
                mentions, tags, List.of());
    }

    /**
     * A status with no hashtags.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt, Instant editedAt, Map<UUID, Boolean> mentions) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, editedAt,
                mentions, List.of(), List.of());
    }

    /**
     * A status that hasn't been edited, and mentions nobody.
     */
    public RemoteStatus(
            URI uri, URI url, UUID authorId, String content, String spoilerText, String language, Visibility visibility,
            boolean sensitive, URI inReplyToUri, Instant publishedAt) {
        this(uri, url, authorId, content, spoilerText, language, visibility, sensitive, inReplyToUri, publishedAt, null,
                Map.of(), List.of(), List.of());
    }
}
