package dev.biribiri.elysium.notification;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.notification.internal.ConversationRepository;
import dev.biribiri.elysium.notification.internal.NotificationRepository;
import dev.biribiri.elysium.notification.internal.NotificationRequestRepository;
import dev.biribiri.elysium.notification.internal.NotificationScreening;
import dev.biribiri.elysium.timeline.Markers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class NotificationService {
    private static final int REMOVAL_BATCH_SIZE = 100;
    private final NotificationRepository notifications;
    private final ConversationRepository conversations;
    private final NotificationRequestRepository requests;
    private final NotificationScreening screening;
    private final Markers markers;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;

    NotificationService(NotificationRepository notifications, ConversationRepository conversations,
                        NotificationRequestRepository requests, NotificationScreening screening, Markers markers,
                        PlatformTransactionManager transactionManager, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.requests = requests;
        this.screening = screening;
        this.events = events;
        this.conversations = conversations;
        this.markers = markers;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<Notification> notifications(UUID viewer, NotificationFilter filter, IdPage page) {
        return notifications.page(viewer, filter, page);
    }

    public Optional<Notification> notification(UUID viewer, UUID id) {
        return notifications.find(viewer, id);
    }

    public List<NotificationGroup> groups(UUID viewer, NotificationFilter filter, IdPage page, List<String> groupedTypes) {
        return notifications.groups(viewer, filter, page, groupedTypes);
    }

    public Optional<NotificationGroup> group(UUID viewer, String key) {
        return notifications.group(viewer, key);
    }

    public List<UUID> groupAccounts(UUID viewer, String key) {
        return notifications.groupAccounts(viewer, key);
    }

    public long unreadCount(UUID viewer, NotificationFilter filter, int limit) {
        return notifications.count(viewer, filter.withoutFiltered(), Math.clamp(limit, 1, 1000), notificationPosition(viewer));
    }

    public long unreadGroupCount(UUID viewer, NotificationFilter filter, int limit, List<String> groupedTypes) {
        return notifications.countGroups(viewer, filter.withoutFiltered(), Math.clamp(limit, 1, 1000), notificationPosition(viewer), groupedTypes);
    }

    /**
     * Pleroma's {@code is_seen}, read from the marker that unread counts use, so that a notification counts as unread
     * exactly when it isn't seen. The order is PostgreSQL's, which compares UUIDs as unsigned bytes.
     */
    public Predicate<Notification> seen(UUID viewer) {
        var position = notificationPosition(viewer);
        if (position == null) return notification -> false;
        return notification -> compareUnsigned(notification.id(), position) <= 0;
    }

    /**
     * Pleroma's way of marking notifications as seen, which moves the notifications marker, so that unread counts agree.
     *
     * @return the notifications this made seen, newest first, or none if the marker was already at or past {@code maxId}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<Notification> seeUpTo(UUID viewer, UUID maxId, int limit) {
        var advance = markers.advance(viewer, "notifications", maxId);
        if (!advance.moved()) return List.of();
        return notifications.upTo(viewer, advance.from(), maxId, limit);
    }

    private static int compareUnsigned(UUID a, UUID b) {
        var high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
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

    public NotificationPolicy policy(UUID viewer) {
        return screening.policy(viewer);
    }

    @Transactional
    public NotificationPolicy changePolicy(UUID viewer, NotificationPolicy.Change change) {
        notifications.lock();
        var policy = screening.policy(viewer).with(change);
        screening.save(viewer, policy);
        return policy;
    }

    public NotificationRequest.Summary requestSummary(UUID viewer) {
        return requests.summary(viewer);
    }

    public List<NotificationRequest> requests(UUID viewer, IdPage page) {
        return requests.page(viewer, page);
    }

    public Optional<NotificationRequest> request(UUID viewer, UUID id) {
        return requests.find(viewer, id);
    }

    /**
     * Lets the sender's notifications through from now on, and brings back what was filtered, along with the direct
     * statuses that were kept out of conversations. None of it is pushed, since it isn't new.
     *
     * @return whether the request was found
     */
    @Transactional
    public boolean acceptRequest(UUID viewer, UUID id) {
        notifications.lock();
        var request = requests.find(viewer, id);
        if (request.isEmpty()) return false;
        var sender = request.get().senderId();
        conversations.holdActors(List.of(viewer, sender));
        requests.permit(viewer, sender);
        for (var statusId : requests.unfilter(viewer, sender)) {
            joinConversation(viewer, statusId);
        }
        requests.delete(viewer, sender);
        return true;
    }

    /**
     * As {@code Notifier} would have added it, had the sender been accepted when the status arrived.
     */
    private void joinConversation(UUID viewer, UUID statusId) {
        var preview = conversations.source(statusId);
        if (preview.isEmpty() || !preview.get().direct()) return;
        var people = new ArrayList<>(conversations.mentions(statusId));
        people.add(preview.get().authorId());
        conversations.holdActors(people);
        conversations.holdSource(statusId)
                .filter(source -> conversations.localRecipients(people).contains(viewer))
                .filter(source -> !notifications.isHidden(viewer, source.authorId()))
                .flatMap(source -> conversations.add(source, viewer, people))
                .ifPresent(conversation -> events.publishEvent(new ConversationUpdated(conversation, viewer)));
    }

    /**
     * @return whether the request was found
     */
    @Transactional
    public boolean dismissRequest(UUID viewer, UUID id) {
        notifications.lock();
        var request = requests.find(viewer, id);
        if (request.isEmpty()) return false;
        requests.dismissFiltered(viewer, request.get().senderId());
        requests.delete(viewer, request.get().senderId());
        return true;
    }

    /**
     * Those that aren't found are left out, as on Mastodon.
     */
    @Transactional
    public void acceptRequests(UUID viewer, List<UUID> ids) {
        for (var id : ids) acceptRequest(viewer, id);
    }

    @Transactional
    public void dismissRequests(UUID viewer, List<UUID> ids) {
        for (var id : ids) dismissRequest(viewer, id);
    }

    public List<Conversation> conversations(UUID viewer, IdPage page) {
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
        requests.removeForActors(List.of(id));
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
                    requests.removeForActors(batch);
                });
                after = ids.getLast();
            }
        } while (ids.size() == REMOVAL_BATCH_SIZE);
    }

    private UUID notificationPosition(UUID viewer) {
        return markers.lastRead(viewer, "notifications").orElse(null);
    }
}
