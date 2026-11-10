package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * What an author submits to create a status.
 *
 * @param spoilerText the content warning, or null for none
 * @param language    the language code, or null if not set
 * @param inReplyToId the status being replied to, or null if this isn't a reply
 * @param mediaIds    the author's uploads to attach, in order
 */
public record NewStatus(
        String text,
        String spoilerText,
        String language,
        Visibility visibility,
        boolean sensitive,
        UUID inReplyToId,
        List<UUID> mediaIds
) {

    public NewStatus {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }

    public NewStatus(String text, String spoilerText, String language, Visibility visibility, boolean sensitive,
                     UUID inReplyToId) {
        this(text, spoilerText, language, visibility, sensitive, inReplyToId, List.of());
    }
}
