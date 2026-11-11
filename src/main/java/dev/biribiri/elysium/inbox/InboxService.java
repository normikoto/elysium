package dev.biribiri.elysium.inbox;

import dev.biribiri.elysium.auth.AccountActions;
import dev.biribiri.elysium.inbox.internal.ConversationRepository;
import dev.biribiri.elysium.inbox.internal.MarkerRepository;
import dev.biribiri.elysium.inbox.internal.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InboxService {
    private static final int REMOVAL_BATCH_SIZE = 100;
    private static final String ADMIN_REPORT = "admin.report";
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final MarkerRepository markers;
    private final TransactionTemplate transaction;
    private final AccountActions accounts;

    InboxService(NotificationRepository notifications, ConversationRepository conversations, MarkerRepository markers,
                 PlatformTransactionManager transactionManager, AccountActions accounts) {
        this.accounts = accounts;
        this.notifications = notifications;
        this.conversations = conversations;
        this.markers = markers;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<Notification> notifications(UUID viewer, NotificationFilter filter, InboxPage page) {
        return notifications.page(viewer, forViewer(viewer, filter), page);
    }

    public Optional<Notification> notification(UUID viewer, UUID id) {
        return notifications.find(viewer, id).filter(found -> !isAdminOnly(found.type()) || isAdmin(viewer));
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, InboxPage page, List<String> groupedTypes) {
        return notifications.groups(viewer, forViewer(viewer, filter), page, groupedTypes);
    }

    public Optional<NotificationGroup> group(UUID viewer, String key) {
        return notifications.group(viewer, key).filter(found -> !isAdminOnly(found.type()) || isAdmin(viewer));
    }

    public List<UUID> groupAccounts(UUID viewer, String key) {
        return notifications.groupAccounts(viewer, key);
    }

    public long unreadCount(UUID viewer, NotificationFilter filter, int limit) {
        return notifications.count(viewer, forViewer(viewer, filter), Math.clamp(limit, 1, 1000),
                markers.notificationPosition(viewer));
    }

    public long unreadGroupCount(UUID viewer, NotificationFilter filter, int limit, List<String> groupedTypes) {
        return notifications.countGroups(viewer, forViewer(viewer, filter), Math.clamp(limit, 1, 1000),
                markers.notificationPosition(viewer), groupedTypes);
    }

    /**
     * An admin who's no longer one stops seeing reports, since those can include statuses its audience didn't include them
     * in. This is checked each time, as the admin role is, so it applies straight away and comes back with the role.
     */
    private NotificationFilter forViewer(UUID viewer, NotificationFilter filter) {
        if (isAdmin(viewer)) {
            return filter;
        }
        var excluded = new ArrayList<>(filter.excludeTypes());
        excluded.add(ADMIN_REPORT);
        return new NotificationFilter(filter.types(), excluded, filter.accountId());
    }

    private boolean isAdmin(UUID viewer) {
        return !accounts.adminsAmong(List.of(viewer)).isEmpty();
    }

    private static boolean isAdminOnly(String type) {
        return type.equals(ADMIN_REPORT);
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

    @Transactional
    public void removeForActor(UUID id) {
        notifications.lock();
        conversations.removeForActors(List.of(id));
    }

    /** Each batch commits by itself, so that a large server doesn't hold the inbox lock for its whole cleanup. */
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
}
