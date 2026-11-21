package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.BoostRepository;
import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.content.internal.PollRepository;
import dev.biribiri.elysium.content.internal.QuoteRepository;
import dev.biribiri.elysium.content.internal.StatusIds;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.StatusThreads;
import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.RemoteHtml;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Statuses from other servers: storing them as their servers send them, replacing them with their edits, and removing
 * them once their servers delete them. Their HTML is sanitized here, so that nothing unsanitized can be stored. Nothing is
 * published for other servers about any of this, since these statuses aren't this server's to send.
 */
@Service
public class RemoteStatuses {

    private final StatusRepository statuses;
    private final StatusThreads threads;
    private final ActorService actors;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final BoostRepository boosts;
    private final MediaAttachmentRepository media;
    private final MediaAttachments attachments;
    private final QuoteRepository quotes;
    private final PollRepository polls;
    private final CustomEmojis emojis;

    RemoteStatuses(
            StatusRepository statuses,
            StatusThreads threads,
            ActorService actors,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events,
            BoostRepository boosts,
            MediaAttachmentRepository media,
            MediaAttachments attachments,
            QuoteRepository quotes,
            PollRepository polls,
            CustomEmojis emojis) {
        this.statuses = statuses;
        this.threads = threads;
        this.actors = actors;
        this.transaction = new TransactionTemplate(transactionManager);
        this.events = events;
        this.boosts = boosts;
        this.media = media;
        this.attachments = attachments;
        this.quotes = quotes;
        this.polls = polls;
        this.emojis = emojis;
    }

    /**
     * Stores a status from another server. {@link StatusStored} updates local inboxes.
     *
     * <p>Its ID comes from when it arrived, not from its {@code published} date, which the other server chose. Clients ask
     * for statuses newer than the last ID they saw, so one with an older ID would never reach them.
     *
     * @return the stored status's ID, or empty if a status with its URI is already stored
     * @throws IllegalArgumentException if the author isn't an active remote actor
     */
    public Optional<UUID> storeRemote(RemoteStatus remote) {
        return store(remote, false, true);
    }

    /**
     * Like {@link #storeRemote}, for a status that's only stored because a status here quotes it. What it quotes in turn
     * isn't fetched.
     */
    public Optional<UUID> storeQuoted(RemoteStatus remote) {
        return store(remote, false, false);
    }

    /**
     * Like {@link #storeRemote}, for an older status fetched from its author's outbox, or for its thread, or first seen in
     * an edit of it, rather than sent here when it was posted. It's never direct, since it doesn't join a conversation.
     *
     * <p>Its ID comes from its {@code published} date, or now if that's later, so that it sorts among its author's statuses
     * by when it was posted, rather than putting old ones at the top of every timeline. This is the one exception to IDs
     * following commits: a client that's already seen later IDs won't be given it as new, which is what's wanted for an old
     * status. Its {@link StatusStored} says it was backfilled, so the accounts it mentions aren't told about it long after it
     * was posted.
     *
     * @return the stored status's ID, or empty if a status with its URI is already stored
     * @throws IllegalArgumentException if the author isn't an active remote actor
     */
    public Optional<UUID> storeBackfilled(RemoteStatus remote) {
        return store(remote, true, true);
    }

    /**
     * @param fetchQuoted whether what it quotes can be fetched
     */
    private Optional<UUID> store(RemoteStatus remote, boolean backfilled, boolean fetchQuoted) {
        var author = actors.findById(remote.authorId())
                .filter(found -> found.isActive() && !actors.isLocal(found))
                .orElseThrow(() -> new IllegalArgumentException("The author isn't an active remote actor"));
        var html = RemoteText.of(remote.content(), remote.spoilerText(), remote.language());
        // Repeats are common, and this keeps them from waiting for the lock that every new status takes.
        if (statuses.existsByUri(remote.uri())) {
            return Optional.empty();
        }

        return transaction.execute(tx -> {
            statuses.lockUri(remote.uri());
            if (statuses.isRemoteDeleted(remote.uri(), author.uri())) {
                return Optional.<UUID>empty();
            }
            // Inside the lock, so that a reply and its parent arriving together can't miss each other: whichever is stored
            // second sees the other.
            var now = Instant.now();
            var id = backfilled ? StatusIds.backfilled(statuses, remote.publishedAt(), now) : StatusIds.next(statuses, now);
            var parent = Optional.ofNullable(remote.inReplyToUri()).flatMap(statuses::findParentByUriAndLock);
            var status = new Status(
                    id,
                    remote.uri(),
                    author.id(),
                    html.text(),
                    html.content(),
                    html.spoilerText(),
                    html.language(),
                    remote.visibility(),
                    remote.sensitive() || !html.spoilerText().isEmpty(),
                    remote.inReplyToUri() != null,
                    parent.map(StatusRepository.Parent::id).orElse(null),
                    parent.map(StatusRepository.Parent::actorId).orElse(null),
                    remote.inReplyToUri(),
                    remote.url(),
                    remote.publishedAt().truncatedTo(ChronoUnit.MICROS),
                    remote.editedAt() == null ? null : remote.editedAt().truncatedTo(ChronoUnit.MICROS),
                    null,
                    0,
                    remote.quotePolicy(),
                    null,
                    false,
                    ContentType.PLAIN);
            if (!statuses.insert(status)) {
                return Optional.<UUID>empty();
            }
            var mentions = new HashMap<>(remote.mentions());
            mentions.remove(author.id());
            statuses.insertMentions(id, mentions);
            statuses.insertTags(id, remoteTags(remote.tags()));
            emojis.insertForStatus(id, emojis.storeRemote(author.domain(), remote.emojis()).values());
            attachments.addRemote(id, author.id(), remote.attachments());
            if (remote.poll() != null) {
                insertRemotePoll(id, PollService.clean(remote.poll()), now);
            }
            if (remote.quote() != null) {
                insertRemoteQuote(id, remote.quote(), fetchQuoted);
            }
            // Jobs have no order, so replies can be stored before their parent, and quotes before what they quote.
            statuses.linkWaitingReplies(remote.uri(), new StatusRepository.Parent(id, author.id()));
            quotes.linkWaiting(remote.uri(), id, author.id())
                    .forEach(quoting -> events.publishEvent(new QuoteVerificationNeeded(quoting, false)));
            threads.assign(status, remote.inReplyToUri());
            events.publishEvent(new StatusStored(id, backfilled));
            return Optional.of(id);
        });
    }

    /**
     * Every remote quote waits to be checked, even one that needs no approval, since only checking it finds what it quotes,
     * and whether that's its author's own, or this server's.
     */
    void insertRemoteQuote(UUID statusId, RemoteQuote quote, boolean fetchQuoted) {
        var quoted = statuses.findParentByUriAndLock(quote.quotedUri());
        quotes.insert(statusId, quote.quotedUri(), quoted, QuoteState.PENDING, quote.legacy(), quote.authorizationUri(),
                null, null, null);
        events.publishEvent(new QuoteVerificationNeeded(statusId, fetchQuoted));
    }

    /**
     * Replaces a remote status with a version its author's server edited. Only what an author can change in an edit on
     * Mastodon is replaced, so its visibility, its parent and when it was published stay as they were. So do its mentions,
     * which decide who can see a followers-only or direct status, so that an edit can't show it to anyone it wasn't sent
     * to. Its hashtags are replaced, since they don't. The version it replaces is kept. Nothing is published for this edit,
     * since it isn't this server's to send.
     *
     * @return what happened to the edit
     */
    public RemoteEdit.Outcome updateRemote(RemoteEdit edit) {
        var html = RemoteText.of(edit.content(), edit.spoilerText(), edit.language());
        var tags = remoteTags(edit.tags());
        var poll = edit.poll() == null ? null : PollService.clean(edit.poll());
        // Servers also send an Update when nothing an edit can change has, such as when a quote is approved, and that
        // isn't kept as a version, so that the status doesn't show as edited.
        var stored = statuses.findByUriForAuthor(edit.uri(), edit.authorId());
        var domain = actors.findById(edit.authorId()).map(Actor::domain).orElse(null);
        if (stored.isPresent() && isSameVersion(stored.get(), html, edit, tags, poll)) {
            // Emoji aren't part of a version, since the same text shows the same shortcodes, and statuses from before the
            // upgrade that added them have none stored. An image can change with nothing else, too.
            if (domain != null) {
                transaction.executeWithoutResult(tx -> emojis.replaceForStatus(
                        stored.get().id(), emojis.storeRemote(domain, edit.emojis()).values()));
            }
            return RemoteEdit.Outcome.UNCHANGED;
        }
        // One statement, so it needs no lock: a Delete or Create that runs alongside it ends the same in either order. A
        // caller that stores the status after NOT_STORED has to cope with one stored in between. The tags are replaced in
        // the same transaction, while the edit's row lock keeps a concurrent edit waiting.
        var removedMedia = new boolean[1];
        var edited = transaction.execute(tx -> {
            var id = statuses.updateRemote(
                    edit.uri(),
                    edit.authorId(),
                    html.text(),
                    html.content(),
                    html.spoilerText(),
                    html.language(),
                    edit.sensitive() || !html.spoilerText().isEmpty(),
                    edit.editedAt().truncatedTo(ChronoUnit.MICROS));
            id.ifPresent(editedId -> {
                replaceRemotePoll(editedId, poll);
                statuses.replaceTags(editedId, tags);
                if (domain != null) {
                    emojis.replaceForStatus(editedId, emojis.storeRemote(domain, edit.emojis()).values());
                }
                removedMedia[0] = attachments.replaceRemote(editedId, edit.authorId(), edit.attachments());
            });
            return id.isPresent();
        });
        if (removedMedia[0]) {
            attachments.processDeletions();
        }
        if (Boolean.TRUE.equals(edited)) {
            stored.ifPresent(status -> events.publishEvent(
                    new RemoteStatusEdited(status.id(), edit.authorId(), edit.editedAt())));
            return RemoteEdit.Outcome.EDITED;
        }
        return statuses.existsByUri(edit.uri()) ? RemoteEdit.Outcome.UNCHANGED : RemoteEdit.Outcome.NOT_STORED;
    }

    private void insertRemotePoll(UUID statusId, RemotePoll poll, Instant now) {
        polls.insert(statusId, poll.multiple(), false, poll.expiresAt(), poll.closedAt(), poll.votersCount(),
                now.truncatedTo(ChronoUnit.MICROS), poll.options(), poll.counts());
    }

    /**
     * A poll whose options change is a new one, whose votes from here no longer mean anything, as on Mastodon.
     */
    private void replaceRemotePoll(UUID statusId, RemotePoll poll) {
        var old = polls.findAndLock(statusId);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (poll == null) {
            old.ifPresent(found -> polls.delete(statusId));
        } else if (old.isPresent() && old.get().hasOptions(poll.options(), poll.multiple())) {
            polls.refreshRemote(statusId, poll.counts(), poll.votersCount(), poll.expiresAt(), poll.closedAt(), now);
        } else {
            old.ifPresent(found -> polls.delete(statusId));
            insertRemotePoll(statusId, poll, now);
        }
    }

    /**
     * @param poll the edit's poll, as {@link PollService#clean} keeps it
     */
    private boolean isSameVersion(
            Status status, RemoteText html, RemoteEdit edit, Collection<String> tags, RemotePoll poll) {
        var stored = polls.find(status.id());
        if (poll == null ? stored.isPresent()
                : stored.isEmpty() || !stored.get().hasOptions(poll.options(), poll.multiple())) {
            return false;
        }
        if (!html.content().equals(status.content()) || !html.spoilerText().equals(status.spoilerText())
                || !Objects.equals(html.language(), status.language())
                || (edit.sensitive() || !html.spoilerText().isEmpty()) != status.sensitive()) {
            return false;
        }
        if (!Set.copyOf(tags).equals(Set.copyOf(statuses.findTags(List.of(status.id())).getOrDefault(status.id(), List.of())))) {
            return false;
        }
        var current = attachments.findByStatuses(List.of(status.id())).getOrDefault(status.id(), List.of());
        if (current.size() != edit.attachments().size()) {
            return false;
        }
        for (var i = 0; i < current.size(); i++) {
            var had = current.get(i);
            var given = edit.attachments().get(i);
            if (!given.url().equals(had.remoteUrl())
                    || !Objects.equals(MediaAttachments.remoteDescription(given.description()), had.description())
                    || !Objects.equals(given.focus(), had.focus())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Another server's text for a status, made safe to store.
     */
    private record RemoteText(String text, String content, String spoilerText, String language) {

        static RemoteText of(String content, String spoilerText, String language) {
            var sanitized = RemoteHtml.sanitize(content);
            return new RemoteText(
                    RemoteHtml.plainText(sanitized),
                    sanitized,
                    spoilerText == null ? "" : RemoteHtml.inlineText(spoilerText),
                    StatusService.isLanguageCode(language) ? language : null);
        }
    }

    /**
     * Removes a remote status because its author's server deleted it, and remembers that it did, since jobs have no order
     * and its {@code Create} can still be waiting to run. Nothing is sent: other servers hear about it from its author's.
     *
     * @param authorUri the actor whose server said so, which can't remove anyone else's status
     * @return whether a status was removed
     */
    public boolean deleteRemote(URI uri, URI authorUri) {
        var removed = Boolean.TRUE.equals(transaction.execute(tx -> {
            statuses.lockUri(uri);
            statuses.markRemoteDeleted(uri, authorUri);
            return deleteLocked(uri, authorUri);
        }));
        if (removed) {
            attachments.processDeletions();
        }
        return removed;
    }

    /**
     * Deletes a remote status once {@link StatusRepository#lockUri} is held, as an admin's removal of one does too.
     */
    boolean deleteLocked(URI uri, URI authorUri) {
        // Locked so that a boost made meanwhile is either found here or fails to find the status.
        var boosted = statuses.findForDeleteByUri(uri);
        var localBoosts = boosted.map(status -> boosts.findLocal(status.id())).orElse(List.of());
        var attached = boosted.map(status -> media.findByStatusAndLock(status.id())).orElse(List.of());
        var deleted = statuses.deleteRemote(uri, authorUri);
        if (deleted) {
            boosted.ifPresent(status -> events.publishEvent(new RemoteStatusDeleted(status.id())));
            media.removeDetached(attached.stream().map(MediaAttachment::id).toList());
            // Only once it's known to be the author's, since anyone can name any status.
            boosted.ifPresent(status -> localBoosts.forEach(boost ->
                    events.publishEvent(StatusUnreblogged.of(boost, uri, status.actorId()))));
        }
        return deleted;
    }

    /**
     * Whether this actor has a stored remote status with this URI that anyone could see, which its server would show to
     * anyone who asks.
     */
    public boolean isPublicRemote(URI uri, URI authorUri) {
        return statuses.isPublicRemote(uri, authorUri);
    }

    /**
     * @return the author of a stored status, if they're neither suspended nor deleted
     */
    public Optional<URI> findAuthorOf(URI statusUri) {
        return statuses.findAuthorUriByUri(statusUri);
    }

    /**
     * Whether a status with this URI is stored, whoever can see it.
     */
    public boolean isStored(URI uri) {
        return statuses.existsByUri(uri);
    }

    /**
     * Another server's tags are only checked here, so that what counts as a tag is decided in one place.
     */
    private static Collection<String> remoteTags(Collection<String> names) {
        return Hashtags.parseAll(names);
    }
}
