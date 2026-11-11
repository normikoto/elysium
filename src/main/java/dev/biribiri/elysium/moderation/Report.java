package dev.biribiri.elysium.moderation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A report to this server's admins about an account.
 *
 * @param reporterId  who reported it: a local account, or for a report from another server, whoever sent it there,
 *                    usually that server's instance actor
 * @param comment     plain text, empty for none
 * @param forwarded   whether it was sent on to the target's server
 * @param activityUri the {@code Flag} it came in, for a report from another server, or null
 * @param assignedTo  the admin handling it, or null
 * @param resolvedBy  the admin who resolved it, or null if it isn't resolved or that admin's account is gone
 * @param resolvedAt  null while it's open
 * @param statusIds   the target's statuses that it's about, oldest first, leaving out those that have gone since
 */
public record Report(
        UUID id,
        UUID reporterId,
        UUID targetId,
        String comment,
        ReportCategory category,
        boolean forwarded,
        String activityUri,
        UUID assignedTo,
        UUID resolvedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt,
        List<UUID> statusIds) {

    public boolean isResolved() {
        return resolvedAt != null;
    }

    public boolean isRemote() {
        return activityUri != null;
    }
}
