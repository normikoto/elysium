package dev.biribiri.elysium.inbox;

import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.content.StatusThreadsMerged;
import dev.biribiri.elysium.graph.FollowEstablished;
import dev.biribiri.elysium.inbox.internal.ConversationRepository;
import dev.biribiri.elysium.inbox.internal.NotificationRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class InboxProjection {
    private final ConversationRepository conversations;
    private final NotificationRepository notifications;

    InboxProjection(ConversationRepository conversations, NotificationRepository notifications) {
        this.conversations = conversations;
        this.notifications = notifications;
    }

    @ApplicationModuleListener
    public void on(StatusStored event) {
        // Most stored statuses involve nobody here, so they're turned away before the lock every inbox write queues on.
        if (!concernsAnyoneHere(event.statusId())) return;
        notifications.lock();
        // Read again under the lock, since a domain block's cleanup can have finished since.
        var preview = conversations.source(event.statusId());
        if (preview.isEmpty()) return;
        var mentioned = conversations.mentions(event.statusId());
        var people = people(mentioned, preview.get().authorId());
        conversations.holdActors(people);
        conversations.holdSource(event.statusId()).ifPresent(source -> {
            for (var recipient : conversations.localRecipients(mentioned)) {
                if (!recipient.equals(source.authorId())) {
                    notifications.insert(recipient, source.authorId(), "mention", source.id(), source.id(), null);
                }
            }
            if (source.direct()) {
                for (var recipient : conversations.localRecipients(people)) {
                    conversations.add(source, recipient, people);
                }
            }
        });
    }

    private boolean concernsAnyoneHere(UUID statusId) {
        var source = conversations.source(statusId);
        if (source.isEmpty()) return false;
        var mentioned = conversations.mentions(statusId);
        var here = conversations.localRecipients(people(mentioned, source.get().authorId()));
        return source.get().direct() ? !here.isEmpty()
                : here.stream().anyMatch(id -> mentioned.contains(id) && !id.equals(source.get().authorId()));
    }

    private static List<UUID> people(List<UUID> mentioned, UUID author) {
        var people = new ArrayList<>(mentioned);
        people.add(author);
        return people;
    }

    @ApplicationModuleListener
    public void on(FollowEstablished event) {
        notifications.lock();
        conversations.holdActors(List.of(event.followerId(), event.followedId()));
        if (conversations.currentFollow(event.followId(), event.followerId(), event.followedId())) {
            notifications.insert(event.followedId(), event.followerId(), "follow", event.followId(), null, event.followId());
        }
    }

    @ApplicationModuleListener
    public void on(StatusThreadsMerged event) {
        notifications.lock();
        conversations.reconcile(event.previousId(), event.threadId());
    }
}
