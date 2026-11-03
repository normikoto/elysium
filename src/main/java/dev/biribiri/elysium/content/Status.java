package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A post.
 *
 * @param text               the source text as the author wrote it, after normalizing
 * @param content            the text rendered as HTML, which is safe to show as is
 * @param language           the language code, or null if not set
 * @param reply              whether this was written as a reply, which stays true after the parent is deleted
 * @param inReplyToId        the parent status, or null if this isn't a reply or the parent was deleted
 * @param inReplyToAccountId the parent's author, or null if this isn't a reply or that account was deleted
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
        Instant publishedAt,
        long repliesCount
) {

    /**
     * Follows don't exist yet, so private and direct statuses can only be seen by their author.
     */
    public boolean isVisibleTo(Optional<UUID> viewer) {
        return visibility == Visibility.PUBLIC
                || visibility == Visibility.UNLISTED
                || viewer.filter(actorId::equals).isPresent();
    }
}
