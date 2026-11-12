package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * A status as admin pages show it.
 *
 * @param shown        whether its content is shown, which depends on why an admin is looking at it
 * @param content      sanitized HTML, as it's shown on its public page
 * @param descriptions one for each attachment, empty for one without
 */
public record ModeratedStatus(UUID id, String handle, boolean local, boolean authorSuspended, String visibility,
                              String publishedAt, URI link, boolean shown, String spoilerText, String content,
                              List<String> descriptions) {
}
