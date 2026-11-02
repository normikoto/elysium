package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * What an author submits to create a status.
 *
 * @param spoilerText the content warning, or null for none
 * @param language    the language code, or null if not set
 * @param inReplyToId the status being replied to, or null if this isn't a reply
 */
public record NewStatus(
        String text,
        String spoilerText,
        String language,
        Visibility visibility,
        boolean sensitive,
        UUID inReplyToId
) {
}
