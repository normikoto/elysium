package dev.biribiri.elysium.inbox;

import dev.biribiri.elysium.content.StatusEdited;
import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.content.StatusLiked;
import dev.biribiri.elysium.content.StatusReblogged;
import dev.biribiri.elysium.content.StatusThreadsMerged;
import dev.biribiri.elysium.graph.FollowEstablished;
import dev.biribiri.elysium.inbox.internal.ConversationRepository;
import dev.biribiri.elysium.inbox.internal.NotificationRepository;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class InboxProjection {
    private final ConversationRepository conversations;
    private final NotificationRepository notifications;

    InboxProjection(ConversationRepository conversations, NotificationRepository notifications) {
        this.conversations = conversations;
        this.notifications = notifications;
    }

    /**
     * A backfilled status is old, and only public or unlisted, so it neither notifies nor joins a conversation.
     */
    @ApplicationModuleListener
    public void on(StatusStored event) {
        if (event.backfilled()) return;
        project(event.statusId(), recipient -> true);
    }

    /**
     * Only the accounts an edit mentions for the first time hear about it, as on Mastodon, so that one who's already been
     * told isn't told again.
     */
    @ApplicationModuleListener
    public void on(StatusEdited event) {
        if (event.addedMentions().isEmpty()) return;
        project(event.statusId(), Set.copyOf(event.addedMentions())::contains);
    }

    /**
     * @param told which of the local accounts it concerns to notify, and add it to a conversation for
     */
    private void project(UUID statusId, Predicate<UUID> told) {
        // Most stored statuses involve nobody here, so they're turned away before the lock every inbox write queues on.
        if (!concernsAnyoneHere(statusId)) return;
        notifications.lock();
        // Read again under the lock, since a domain block's cleanup can have finished since.
        var preview = conversations.source(statusId);
        if (preview.isEmpty()) return;
        var mentioned = conversations.mentions(statusId);
        var people = people(mentioned, preview.get().authorId());
        conversations.holdActors(people);
        conversations.holdSource(statusId).ifPresent(source -> {
            for (var recipient : conversations.localRecipients(mentioned)) {
                if (!recipient.equals(source.authorId()) && told.test(recipient)) {
                    notifications.insert(recipient, source.authorId(), "mention", source.id(), source.id(), null);
                }
            }
            if (source.direct()) {
                for (var recipient : conversations.localRecipients(people)) {
                    if (told.test(recipient)) {
                        conversations.add(source, recipient, people);
                    }
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
    public void on(StatusLiked event) {
        if (event.actorId().equals(event.authorId())
                || conversations.localRecipients(List.of(event.authorId())).isEmpty()) return;
        notifications.lock();
        conversations.holdActors(List.of(event.actorId(), event.authorId()));
        conversations.holdSource(event.statusId()).filter(source -> source.authorId().equals(event.authorId()))
                .filter(source -> !conversations.localRecipients(List.of(event.authorId())).isEmpty())
                .filter(source -> conversations.currentLike(event.likeId(), event.actorId(), event.statusId()))
                .ifPresent(source -> notifications.insertFavourite(event.authorId(), event.actorId(), event.likeId(), source.id()));
    }

    @ApplicationModuleListener
    public void on(StatusReblogged event) {
        if (event.actorId().equals(event.authorId())
                || conversations.localRecipients(List.of(event.authorId())).isEmpty()) return;
        notifications.lock();
        conversations.holdActors(List.of(event.actorId(), event.authorId()));
        conversations.holdSource(event.statusId()).filter(source -> source.authorId().equals(event.authorId()))
                .filter(source -> !conversations.localRecipients(List.of(event.authorId())).isEmpty())
                .filter(source -> conversations.currentReblog(event.boostId(), event.actorId(), event.statusId()))
                .ifPresent(source -> notifications.insertReblog(event.authorId(), event.actorId(), event.boostId(), source.id()));
    }

    @ApplicationModuleListener
    public void on(StatusThreadsMerged event) {
        notifications.lock();
        conversations.reconcile(event.previousId(), event.threadId());
    }
}
