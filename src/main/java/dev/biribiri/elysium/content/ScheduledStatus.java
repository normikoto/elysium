package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A status that a local account has asked to be posted later.
 *
 * @param draft what it's to be posted with, as it was given, whose media is what's held for it
 * @param media the uploads held for it, in order
 */
public record ScheduledStatus(UUID id, UUID authorId, Instant scheduledAt, NewStatus draft, List<MediaAttachment> media) {

    public ScheduledStatus {
        media = List.copyOf(media);
    }
}
