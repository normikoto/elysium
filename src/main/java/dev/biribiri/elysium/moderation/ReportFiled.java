package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * A new report has been stored, so admins have to hear about it, and it may have to be sent on to the target's server.
 */
public record ReportFiled(UUID reportId) {
}
