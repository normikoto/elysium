package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A new version of a status from another server, as it's been checked and read from there.
 *
 * @param content     HTML, which hasn't been sanitized yet
 * @param spoilerText HTML, which is reduced to plain text, as clients show it, or null if it has none
 * @param language    a language code, or null if not given or not valid
 * @param editedAt    when its server says it was edited, which orders edits that arrive out of order
 * @param tags        the names of its hashtags as its server gave them, which are checked and normalized
 *                    when it's stored
 * @param attachments every attachment it has now, in order, which replace the ones it had
 * @param poll        its poll now, or null if it has none
 */
public record RemoteEdit(
        URI uri,
        UUID authorId,
        String content,
        String spoilerText,
        String language,
        boolean sensitive,
        Instant editedAt,
        Collection<String> tags,
        List<RemoteAttachment> attachments,
        RemotePoll poll
) {

    public RemoteEdit {
        tags = List.copyOf(tags);
        attachments = List.copyOf(attachments);
    }

    /**
     * An edit that leaves the status with no poll.
     */
    public RemoteEdit(
            URI uri, UUID authorId, String content, String spoilerText, String language, boolean sensitive,
            Instant editedAt, Collection<String> tags, List<RemoteAttachment> attachments) {
        this(uri, authorId, content, spoilerText, language, sensitive, editedAt, tags, attachments, null);
    }

    /**
     * An edit that leaves the status with no attachments.
     */
    public RemoteEdit(
            URI uri, UUID authorId, String content, String spoilerText, String language, boolean sensitive,
            Instant editedAt, Collection<String> tags) {
        this(uri, authorId, content, spoilerText, language, sensitive, editedAt, tags, List.of());
    }

    /**
     * An edit that leaves the status with no hashtags.
     */
    public RemoteEdit(
            URI uri, UUID authorId, String content, String spoilerText, String language, boolean sensitive,
            Instant editedAt) {
        this(uri, authorId, content, spoilerText, language, sensitive, editedAt, List.of(), List.of());
    }

    /**
     * What {@link StatusService#updateRemote} did with an edit.
     */
    public enum Outcome {
        /** The stored status was replaced. */
        EDITED,
        /**
         * A status with this URI is stored, but it's someone else's, was already edited at this time or later, or was
         * published at this time or later.
         */
        UNCHANGED,
        /** No status with this URI is stored. */
        NOT_STORED
    }
}
