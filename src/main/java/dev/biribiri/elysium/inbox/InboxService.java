package dev.biribiri.elysium.inbox;

import dev.biribiri.elysium.inbox.internal.ConversationRepository;
import dev.biribiri.elysium.inbox.internal.MarkerRepository;
import dev.biribiri.elysium.inbox.internal.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InboxService {
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final MarkerRepository markers;

    InboxService(NotificationRepository notifications, ConversationRepository conversations, MarkerRepository markers) {
        this.notifications = notifications;
        this.conversations = conversations;
        this.markers = markers;
    }

    public List<Notification> notifications(UUID viewer, NotificationFilter filter, InboxPage page) {
        return notifications.page(viewer, filter, page);
    }

    public Optional<Notification> notification(UUID viewer, UUID id) {
        return notifications.find(viewer, id);
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, InboxPage page, List<String> groupedTypes) {
        return notifications.groups(viewer, filter, page, groupedTypes);
    }

    public Optional<NotificationGroup> group(UUID viewer, String key) {
        return notifications.group(viewer, key);
    }

    public List<UUID> groupAccounts(UUID viewer, String key) {
        return notifications.groupAccounts(viewer, key);
    }

    public long unreadCount(UUID viewer, NotificationFilter filter, int limit) {
        return notifications.count(viewer, filter, Math.clamp(limit, 1, 1000), markers.notificationPosition(viewer));
    }

    public long unreadGroupCount(UUID viewer, NotificationFilter filter, int limit, List<String> groupedTypes) {
        return notifications.countGroups(viewer, filter, Math.clamp(limit, 1, 1000), markers.notificationPosition(viewer), groupedTypes);
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

    public List<Conversation> conversations(UUID viewer, InboxPage page) {
        return conversations.page(viewer, page);
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

    public Map<String, Marker> markers(UUID viewer, List<String> timelines) {
        return markers.find(viewer, timelines);
    }

    @Transactional
    public Map<String, Marker> saveMarkers(UUID viewer, Map<String, UUID> positions) {
        return markers.save(viewer, positions);
    }

    public List<String> remoteDomains() {
        return conversations.remoteDomains();
    }

    @Transactional
    public void removeForActor(UUID id) {
        notifications.lock();
        conversations.removeForActor(id);
    }

    @Transactional
    public void removeForDomain(String domain) {
        for (var id : conversations.actorsOn(domain)) removeForActor(id);
    }
}
