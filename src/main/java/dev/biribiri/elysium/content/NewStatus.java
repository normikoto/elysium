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
 * @param quotedStatusId the status to quote, or null for none
 * @param quotePolicy who can quote this status, or null for the author's default
 * @param poll        its poll, or null for none
 */
public record NewStatus(
        String text,
        String spoilerText,
        String language,
        Visibility visibility,
        boolean sensitive,
        UUID inReplyToId,
        List<UUID> mediaIds,
        UUID quotedStatusId,
        QuotePolicy quotePolicy,
        NewPoll poll
) {

    public NewStatus {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }

    public NewStatus(String text, String spoilerText, String language, Visibility visibility, boolean sensitive,
                     UUID inReplyToId) {
        this(text, spoilerText, language, visibility, sensitive, inReplyToId, List.of());
    }

    public NewStatus(String text, String spoilerText, String language, Visibility visibility, boolean sensitive,
                     UUID inReplyToId, List<UUID> mediaIds) {
        this(text, spoilerText, language, visibility, sensitive, inReplyToId, mediaIds, null, null);
    }

    /**
     * A status with no poll.
     */
    public NewStatus(String text, String spoilerText, String language, Visibility visibility, boolean sensitive,
                     UUID inReplyToId, List<UUID> mediaIds, UUID quotedStatusId, QuotePolicy quotePolicy) {
        this(text, spoilerText, language, visibility, sensitive, inReplyToId, mediaIds, quotedStatusId, quotePolicy, null);
    }
}
