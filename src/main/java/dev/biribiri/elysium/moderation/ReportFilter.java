package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * Which reports to list.
 *
 * @param resolved   whether to list resolved reports rather than open ones
 * @param reporterId only reports by this account, or null
 * @param targetId   only reports about this account, or null
 */
public record ReportFilter(boolean resolved, UUID reporterId, UUID targetId) {
}
