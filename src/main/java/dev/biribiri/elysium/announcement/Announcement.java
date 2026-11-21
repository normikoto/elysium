package dev.biribiri.elysium.announcement;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin's announcement to the people on this server.
 *
 * @param text        plain text
 * @param startsAt    when what's announced starts, for clients to show, or null with {@code endsAt}
 * @param endsAt      when it ends, or null with {@code startsAt}
 * @param allDay      whether clients show only the dates
 * @param publishedAt when it was last published, or null for a draft, which clients aren't shown
 */
public record Announcement(
        UUID id,
        String text,
        Instant startsAt,
        Instant endsAt,
        boolean allDay,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isPublished() {
        return publishedAt != null;
    }
}
