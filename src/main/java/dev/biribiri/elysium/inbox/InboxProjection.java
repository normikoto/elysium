package dev.biribiri.elysium.inbox;

import dev.biribiri.elysium.auth.AccountActions;
import dev.biribiri.elysium.content.PollEnded;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.QuoteAccepted;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.RemoteStatusEdited;
import dev.biribiri.elysium.content.StatusEdited;
import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.content.StatusLiked;
import dev.biribiri.elysium.content.StatusReblogged;
import dev.biribiri.elysium.content.StatusThreadsMerged;
import dev.biribiri.elysium.graph.AccountBlocked;
import dev.biribiri.elysium.graph.FollowEstablished;
import dev.biribiri.elysium.graph.FollowRequestAuthorized;
import dev.biribiri.elysium.graph.FollowRequestReceived;
import dev.biribiri.elysium.inbox.internal.ConversationRepository;
import dev.biribiri.elysium.inbox.internal.NotificationRepository;
import dev.biribiri.elysium.moderation.ReportFiled;
import dev.biribiri.elysium.moderation.Reports;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@Service
public class InboxProjection {
    private final ConversationRepository conversations;
    private final NotificationRepository notifications;
    private final QuoteService quotes;
    private final Reports reports;
    private final AccountActions accounts;
    private final PollService polls;

    InboxProjection(ConversationRepository conversations, NotificationRepository notifications, QuoteService quotes,
                    Reports reports, AccountActions accounts, PollService polls) {
        this.polls = polls;
        this.conversations = conversations;
        this.notifications = notifications;
        this.quotes = quotes;
        this.reports = reports;
        this.accounts = accounts;
    }

    /**
     * Tells the admins there are now, as on Mastodon, except one who filed it. An admin chosen later isn't told about
     * reports from before, but finds them on the admin page.
     */
    @ApplicationModuleListener
    public void on(ReportFiled event) {
        var report = reports.find(event.reportId());
        if (report.isEmpty()) return;
        notifications.lock();
        for (var admin : accounts.activeAdmins()) {
            if (!admin.equals(report.get().reporterId())) {
                notifications.insertReport(admin, report.get().reporterId(), report.get().id());
            }
        }
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
        quotedUpdate(event.statusId(), event.authorId(), event.editedAt());
        if (event.addedMentions().isEmpty()) return;
        project(event.statusId(), Set.copyOf(event.addedMentions())::contains);
    }

    @ApplicationModuleListener
    public void on(RemoteStatusEdited event) {
        quotedUpdate(event.statusId(), event.authorId(), event.editedAt());
    }

    /**
     * Tells the local authors of accepted quotes of a status that it's been edited, as Mastodon does, since what they
     * quoted has changed. Each edit is told once, however often this runs.
     */
    private void quotedUpdate(UUID statusId, UUID editorId, Instant editedAt) {
        var quoting = new HashMap<>(quotes.findLocalQuotes(statusId));
        quoting.values().removeIf(editorId::equals);
        if (quoting.isEmpty()) return;
        notifications.lock();
        conversations.holdActors(people(List.copyOf(quoting.values()), editorId));
        quoting.forEach((quoteId, authorId) -> {
            conversations.holdSource(quoteId).filter(source -> source.authorId().equals(authorId))
                    .ifPresent(source -> notifications.insertQuotedUpdate(authorId, editorId,
                            UUID.nameUUIDFromBytes((quoteId + "\n" + editedAt.toEpochMilli())
                                    .getBytes(StandardCharsets.UTF_8)), quoteId));
        });
    }

    /**
     * Tells a local author that their status was quoted, once the quote is approved, or needed no approval.
     */
    @ApplicationModuleListener
    public void on(QuoteAccepted event) {
        if (event.authorId().equals(event.quotedAuthorId())
                || conversations.localRecipients(List.of(event.quotedAuthorId())).isEmpty()) return;
        notifications.lock();
        conversations.holdActors(List.of(event.authorId(), event.quotedAuthorId()));
        conversations.holdSource(event.statusId()).filter(source -> source.authorId().equals(event.authorId()))
                .ifPresent(source -> notifications.insertQuote(event.quotedAuthorId(), event.authorId(), source.id()));
    }

    /**
     * Tells the local voters in a poll that it's ended, and its author if they're local, as on Mastodon. The author is
     * who it's from, so a voter who mutes them isn't told.
     */
    @ApplicationModuleListener
    public void on(PollEnded event) {
        var told = new ArrayList<>(conversations.localRecipients(polls.findVoters(event.statusId())));
        if (event.local() && !told.contains(event.authorId())) {
            told.add(event.authorId());
        }
        if (told.isEmpty()) return;
        notifications.lock();
        conversations.holdActors(people(told, event.authorId()));
        conversations.holdSource(event.statusId()).filter(source -> source.authorId().equals(event.authorId()))
                .ifPresent(source -> told.forEach(
                        recipient -> notifications.insertPoll(recipient, event.authorId(), source.id())));
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
                    if (told.test(recipient)
                            && (recipient.equals(source.authorId()) || !notifications.isHidden(recipient, source.authorId()))) {
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

    /**
     * Requests are normally approved through {@link FollowRequestAuthorized}, which adds no notification, including when
     * the account stops approving followers by hand. A remote request that's repeated after that, before its approval
     * runs, is accepted as a new follow instead, so it gets this notification in place of its request's.
     */
    @ApplicationModuleListener
    public void on(FollowEstablished event) {
        notifications.lock();
        conversations.holdActors(List.of(event.followerId(), event.followedId()));
        notifications.deleteFollowRequest(event.followId());
        if (conversations.currentFollow(event.followId(), event.followerId(), event.followedId())) {
            notifications.insert(event.followedId(), event.followerId(), "follow", event.followId(), null, event.followId());
        }
    }

    @ApplicationModuleListener
    public void on(FollowRequestReceived event) {
        notifications.lock();
        conversations.holdActors(List.of(event.followerId(), event.followedId()));
        if (conversations.currentFollowRequest(event.followId(), event.followerId(), event.followedId())) {
            notifications.insert(
                    event.followedId(), event.followerId(), "follow_request", event.followId(), null, event.followId());
        }
    }

    /**
     * The account approved the request itself, so it isn't told about it as a new follower, as on Mastodon. A rejected or
     * withdrawn request takes its notification with it.
     */
    @ApplicationModuleListener
    public void on(FollowRequestAuthorized event) {
        notifications.lock();
        notifications.deleteFollowRequest(event.followId());
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

    /**
     * What's already been said between the two goes, as on Mastodon, while new notifications are kept out by the block.
     */
    @ApplicationModuleListener
    public void on(AccountBlocked event) {
        notifications.lock();
        notifications.deleteBetween(event.blockerId(), event.blockedId());
    }

    @ApplicationModuleListener
    public void on(StatusThreadsMerged event) {
        notifications.lock();
        conversations.reconcile(event.previousId(), event.threadId());
    }
}
