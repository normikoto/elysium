package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.content.StatusReferences;
import org.springframework.stereotype.Component;

/**
 * A report's statuses are what moderators judge it by, so they're kept for as long as the report is.
 */
@Component
class ReportStatusReferences implements StatusReferences {

    @Override
    public String refersTo(String statusId) {
        return "EXISTS (SELECT 1 FROM report_status r WHERE r.status_id = %s)".formatted(statusId);
    }
}
