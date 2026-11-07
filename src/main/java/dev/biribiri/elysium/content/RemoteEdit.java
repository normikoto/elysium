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
}
