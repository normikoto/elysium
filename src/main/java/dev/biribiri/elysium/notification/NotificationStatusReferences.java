package dev.biribiri.elysium.notification;

import dev.biribiri.elysium.content.StatusReferences;
import org.springframework.stereotype.Component;

/**
 * Notifications and conversations would lose their statuses through a cascade, so other servers' statuses they show are
 * kept for as long as they do.
 */
@Component
class NotificationStatusReferences implements StatusReferences {

    @Override
    public String refersTo(String statusId) {
        return """
                EXISTS (SELECT 1 FROM notification n WHERE n.status_id = %1$s)
                OR EXISTS (SELECT 1 FROM conversation_status c WHERE c.status_id = %1$s)""".formatted(statusId);
    }
}
