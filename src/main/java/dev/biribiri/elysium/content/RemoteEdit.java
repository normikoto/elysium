package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A new version of a status from another server, as it's been checked and read from there.
 *
 * @param content     HTML, which hasn't been sanitized yet
 * @param spoilerText HTML, which is reduced to plain text, as clients show it, or null if it has none
 * @param language    a language code, or null if not given or not valid
 * @param editedAt    when its server says it was edited, which orders edits that arrive out of order
 */
public record RemoteEdit(
        URI uri,
        UUID authorId,
        String content,
        String spoilerText,
        String language,
        boolean sensitive,
        Instant editedAt
) {

    /**
     * What {@link StatusService#updateRemote} did with an edit.
     */
    public enum Outcome {
        /** The stored status was replaced. */
        EDITED,
        /** A status with this URI is stored, but it's someone else's, or was already edited at this time or later. */
        UNCHANGED,
        /** No status with this URI is stored. */
        NOT_STORED
    }
}
