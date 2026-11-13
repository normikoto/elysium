package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * What an author changes in one of their statuses. Its visibility and parent stay as they were, as on Mastodon.
 *
 * @param text         the source text, which replaces the old one
 * @param spoilerText  the content warning, or null or blank for none
 * @param language     a language code, or null or blank to keep the one it has, as on Mastodon
 * @param sensitive    whether it's sensitive, or null to keep what it is. A content warning always makes it sensitive
 * @param mediaIds     every attachment it's to have, in order, which can include new uploads, or null to keep the ones it
 *                     has
 * @param mediaChanges changes to the descriptions and focus of its attachments
 * @param quotedStatusId the status it quotes, or null to keep what it quotes. A quote can't be changed, as on Mastodon
 * @param quotePolicy    who can quote it, or null to keep that
 * @param poll           what to do to its poll, or null to keep the one it has, or that it has none
 */
public record StatusEdit(String text, String spoilerText, String language, Boolean sensitive, List<UUID> mediaIds,
                         List<MediaChange> mediaChanges, UUID quotedStatusId, QuotePolicy quotePolicy,
                         PollChange poll) {

    public StatusEdit {
        mediaIds = mediaIds == null ? null : List.copyOf(mediaIds);
        mediaChanges = mediaChanges == null ? List.of() : List.copyOf(mediaChanges);
    }

    public StatusEdit(String text, String spoilerText, String language, Boolean sensitive) {
        this(text, spoilerText, language, sensitive, null, List.of());
    }

    public StatusEdit(String text, String spoilerText, String language, Boolean sensitive, List<UUID> mediaIds,
                      List<MediaChange> mediaChanges) {
        this(text, spoilerText, language, sensitive, mediaIds, mediaChanges, null, null);
    }

    /**
     * An edit that keeps the status's poll.
     */
    public StatusEdit(String text, String spoilerText, String language, Boolean sensitive, List<UUID> mediaIds,
                      List<MediaChange> mediaChanges, UUID quotedStatusId, QuotePolicy quotePolicy) {
        this(text, spoilerText, language, sensitive, mediaIds, mediaChanges, quotedStatusId, quotePolicy, null);
    }
}
