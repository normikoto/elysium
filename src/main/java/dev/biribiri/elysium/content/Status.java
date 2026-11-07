package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A post.
 *
 * @param text               the source text as the author wrote it, after normalizing. For a status from another
 *                           server, it's the text its HTML shows, since the source isn't sent
 * @param content            the text rendered as HTML, or another server's HTML after sanitizing, so it's safe to show as
 *                           is
 * @param language           the language code, or null if not set
 * @param reply              whether this was written as a reply, which stays true after the parent is deleted
 * @param inReplyToId        the parent status, or null if this isn't a reply or the parent was deleted
 * @param inReplyToAccountId the parent's author, or null if this isn't a reply or that account was deleted
 * @param inReplyToUri       the parent's URI, for a reply from another server, which is kept when the parent isn't
 *                           stored here. Null for a local status
 * @param url                the page that shows a status from another server, or null if it didn't give one, or for a
 *                           local status, whose page is its URI
 * @param repliesCount       the number of public and unlisted replies
 */
public record Status(
        UUID id,
        URI uri,
        UUID actorId,
        String text,
        String content,
        String spoilerText,
        String language,
        Visibility visibility,
        boolean sensitive,
        boolean reply,
        UUID inReplyToId,
        UUID inReplyToAccountId,
        URI inReplyToUri,
        URI url,
        Instant publishedAt,
        long repliesCount
) {

    /**
     * Whether a viewer can see this status without knowing who follows whom. Use {@link StatusService#isVisible}, which also
     * lets followers see followers-only statuses, unless the viewer is anonymous or is the author.
     */
    public boolean isVisibleTo(Optional<UUID> viewer) {
        return visibility == Visibility.PUBLIC
                || visibility == Visibility.UNLISTED
                || viewer.filter(actorId::equals).isPresent();
    }
}
