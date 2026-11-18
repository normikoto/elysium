package dev.biribiri.elysium.notification;

import dev.biribiri.elysium.notification.internal.ConversationRepository;
import dev.biribiri.elysium.notification.internal.NotificationRepository;
import dev.biribiri.elysium.timeline.Markers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NotificationService {
    private static final int REMOVAL_BATCH_SIZE = 100;
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final Markers markers;
    private final TransactionTemplate transaction;

    NotificationService(NotificationRepository notifications, ConversationRepository conversations, Markers markers,
                        PlatformTransactionManager transactionManager) {
        this.notifications = notifications;
        this.conversations = conversations;
        this.markers = markers;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<Notification> notifications(UUID viewer, NotificationFilter filter, NotificationPage page) {
        return notifications.page(viewer, filter, page);
    }

    public Optional<Notification> notification(UUID viewer, UUID id) {
        return notifications.find(viewer, id);
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, NotificationPage page, List<String> groupedTypes) {
        return notifications.groups(viewer, filter, page, groupedTypes);
    }

    public Optional<NotificationGroup> group(UUID viewer, String key) {
        return notifications.group(viewer, key);
    }

    public List<UUID> groupAccounts(UUID viewer, String key) {
        return notifications.groupAccounts(viewer, key);
    }

    public long unreadCount(UUID viewer, NotificationFilter filter, int limit) {
        return notifications.count(viewer, filter, Math.clamp(limit, 1, 1000), notificationPosition(viewer));
    }

    public long unreadGroupCount(UUID viewer, NotificationFilter filter, int limit, List<String> groupedTypes) {
        return notifications.countGroups(viewer, filter, Math.clamp(limit, 1, 1000), notificationPosition(viewer), groupedTypes);
    }

    @Transactional
    public boolean dismiss(UUID viewer, UUID id) {
        notifications.lock();
        return notifications.dismiss(viewer, id);
    }

    @Transactional
    public void clear(UUID viewer) {
        notifications.lock();
        notifications.clear(viewer);
    }

    @Transactional
    public void dismissGroup(UUID viewer, String key) {
        notifications.lock();
        notifications.dismissGroup(viewer, key);
    }

    public List<Conversation> conversations(UUID viewer, NotificationPage page) {
        return conversations.page(viewer, page);
    }

    public Optional<Conversation> conversation(UUID viewer, UUID id) {
        return conversations.find(viewer, id);
    }

    @Transactional
    public Optional<Conversation> readConversation(UUID viewer, UUID id) {
        notifications.lock();
        return conversations.read(viewer, id);
    }

    @Transactional
    public boolean hideConversation(UUID viewer, UUID id) {
        notifications.lock();
        return conversations.hide(viewer, id);
    }

    @Transactional
    public void removeForActor(UUID id) {
        notifications.lock();
        conversations.removeForActors(List.of(id));
    }

    /** Each batch commits by itself, so that a large server doesn't hold the notification lock for its whole cleanup. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void removeForDomain(String domain) {
        var after = new UUID(0, 0);
        List<UUID> ids;
        do {
            ids = conversations.actorsOn(domain, after, REMOVAL_BATCH_SIZE);
            if (!ids.isEmpty()) {
                var batch = ids;
                transaction.executeWithoutResult(tx -> {
                    notifications.lock();
                    conversations.removeForActors(batch);
                });
                after = ids.getLast();
            }
        } while (ids.size() == REMOVAL_BATCH_SIZE);
    }

    private UUID notificationPosition(UUID viewer) {
        return markers.lastRead(viewer, "notifications").orElse(null);
    }
}
