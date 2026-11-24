package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.internal.BoostRepository;
import dev.biribiri.elysium.content.internal.FollowersOnlyRecipientRepository;
import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.content.internal.PollRepository;
import dev.biribiri.elysium.content.internal.QuoteRepository;
import dev.biribiri.elysium.content.internal.StatusIds;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.StatusThreads;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.Markdown;
import dev.biribiri.elysium.text.Mentions;
import dev.biribiri.elysium.text.PlainText;
import dev.biribiri.elysium.text.Urls;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class StatusService {

    /**
     * The most ancestors that {@link #findContext} lists, which is as many as fetching a thread fills in.
     */
    public static final int MAX_ANCESTORS = 40;

    /**
     * The most descendants that {@link #findContext} lists, which is as many as fetching a thread fills in.
     */
    public static final int MAX_DESCENDANTS = 60;

    /**
     * How many levels of replies {@link #findContext} follows.
     */
    public static final int MAX_DESCENDANT_DEPTH = 20;

    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8}){0,3}");

    /**
     * The Hangul fillers and the blank Braille pattern, which Unicode doesn't classify as spaces.
     */
    private static final Set<Integer> BLANK_CHARACTERS = Set.of(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0);

    private final StatusRepository statuses;
    private final StatusThreads threads;
    private final ActorService actors;
    private final InstanceProperties instance;
    private final StatusProperties properties;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final FollowService follows;
    private final BlockService blocks;
    private final RemoteActorResolver resolver;
    private final BoostRepository boosts;
    private final MediaAttachmentRepository media;
    private final MediaAttachments attachments;
    private final AttachmentProperties attachmentProperties;
    private final FollowersOnlyRecipientRepository followersOnly;
    private final QuoteRepository quotes;
    private final QuoteRules quoteRules;
    private final PostingDefaults postingDefaults;
    private final PollRepository polls;
    private final CustomEmojis emojis;
    private final RemoteStatuses remoteStatuses;

    StatusService(
            StatusRepository statuses,
            StatusThreads threads,
            ActorService actors,
            InstanceProperties instance,
            StatusProperties properties,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events,
            FollowService follows,
            BlockService blocks,
            RemoteActorResolver resolver,
            BoostRepository boosts,
            MediaAttachmentRepository media,
            MediaAttachments attachments,
            AttachmentProperties attachmentProperties,
            FollowersOnlyRecipientRepository followersOnly,
            QuoteRepository quotes,
            QuoteRules quoteRules,
            PostingDefaults postingDefaults,
            PollRepository polls,
            CustomEmojis emojis,
            RemoteStatuses remoteStatuses) {
        this.remoteStatuses = remoteStatuses;
        this.polls = polls;
        this.emojis = emojis;
        this.followersOnly = followersOnly;
        this.quotes = quotes;
        this.quoteRules = quoteRules;
        this.postingDefaults = postingDefaults;
        this.media = media;
        this.attachments = attachments;
        this.attachmentProperties = attachmentProperties;
        this.resolver = resolver;
        this.boosts = boosts;
        this.statuses = statuses;
        this.threads = threads;
        this.actors = actors;
        this.follows = follows;
        this.blocks = blocks;
        this.instance = instance;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.events = events;
    }

    /**
     * Publishes a status. Mentions of accounts on other servers are looked up through WebFinger first, which counts toward
     * the author's {@link RemoteActorResolver#LOOKUPS_PER_MINUTE}. A mention that can't be found, including once the author
     * has used up their lookups, stays as text, as it does on Mastodon, rather than refusing the status.
     *
     * @throws InvalidStatusException       if the status breaks a validation rule
     * @throws ReplyParentNotFoundException if the status being replied to doesn't exist or the author can't see it
     * @throws QuotedStatusNotFoundException if the status to quote doesn't exist or the author can't see it
     */
    public Status create(UUID authorId, NewStatus draft) {
        return create(authorId, draft, mediaIds -> Optional.of(claimMedia(mediaIds, authorId))).orElseThrow();
    }

    /**
     * Runs the checks that {@link #create} would, without posting anything or looking up mentions, for a status to be
     * posted later. Its media isn't checked against the author's uploads, which is left to whoever holds them.
     *
     * @throws InvalidStatusException       if the status breaks a validation rule
     * @throws ReplyParentNotFoundException if the status being replied to doesn't exist or the author can't see it
     * @throws QuotedStatusNotFoundException if the status to quote doesn't exist or the author can't see it
     */
    void check(UUID authorId, NewStatus draft) {
        prepare(authorId, draft);
    }

    /**
     * Like {@link #create}, for a status whose media comes from somewhere other than the author's unattached uploads.
     *
     * @param claim called in the transaction that creates the status, before anything is written, with the media IDs the
     *              draft has. It returns the media to attach, in order, or empty to post nothing
     * @return the status, or empty if {@code claim} said not to post it
     */
    Optional<Status> create(UUID authorId, NewStatus draft, Function<List<UUID>, Optional<List<UUID>>> claim) {
        var prepared = prepare(authorId, draft);
        var text = prepared.text();
        var spoilerText = prepared.spoilerText();
        var language = prepared.language();
        var mediaIds = prepared.mediaIds();
        var poll = prepared.poll();
        var author = prepared.author();
        var quoting = prepared.quoting();
        var localOnly = prepared.localOnly();
        // Quoting a followers-only status, which only its author can, keeps the quote among the same followers.
        var visibility = quoting != null && quoting.status().visibility() == Visibility.PRIVATE && draft.visibility().isOpen()
                ? Visibility.PRIVATE : draft.visibility();
        var quotePolicy = !visibility.isOpen() ? QuotePolicy.NOBODY
                : draft.quotePolicy() != null ? draft.quotePolicy() : postingDefaults.quotePolicy(authorId);
        var quoteOthers = !visibility.isOpen() ? OtherQuotes.REJECT
                : draft.quoteOthers() != null ? draft.quoteOthers() : postingDefaults.quoteOthers(authorId);
        var contentType = draft.contentType();
        var linkable = linkable(text, contentType);
        var mentioned = resolveMentions(author, linkable);
        var content = quoting == null ? render(text, contentType, mentioned)
                : withQuoteFallback(render(text, contentType, mentioned), text, quoting.status().link(),
                quoting.status().uri());
        var localEmojis = localEmojiIds(text, spoilerText, poll == null ? List.of() : poll.options());

        return transaction.execute(tx -> {
            // Before the status, so that one that can't have them isn't given an ID.
            var claiming = claim.apply(mediaIds);
            if (claiming.isEmpty()) {
                return Optional.<Status>empty();
            }
            var claimed = claiming.get();
            var parent = draft.inReplyToId() == null
                    ? Optional.<Status>empty()
                    : Optional.of(statuses.findByIdAndLock(draft.inReplyToId())
                    .filter(candidate -> isVisible(candidate, Optional.of(authorId)))
                    .orElseThrow(ReplyParentNotFoundException::new));
            // Held so that it can't be deleted before the quote is linked to it.
            var quoted = quoting == null ? null : statuses.findByIdAndLock(quoting.status().id())
                    .filter(candidate -> isVisible(candidate, Optional.of(authorId)))
                    .orElseThrow(QuotedStatusNotFoundException::new);

            // PostgreSQL stores microseconds, so this matches what a later read returns.
            var publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var id = StatusIds.next(statuses, publishedAt);
            var uri = instance.resolve("/users/" + author.username() + "/statuses/" + id);
            var status = new Status(
                    id,
                    uri,
                    authorId,
                    text,
                    content,
                    spoilerText,
                    language,
                    visibility,
                    draft.sensitive() || !spoilerText.isEmpty(),
                    parent.isPresent(),
                    parent.map(Status::id).orElse(null),
                    parent.map(Status::actorId).orElse(null),
                    null,
                    null,
                    publishedAt,
                    null,
                    null,
                    0,
                    quotePolicy.approval(quoteOthers),
                    null,
                    localOnly,
                    contentType);
            statuses.insert(status);
            media.attach(id, authorId, claimed);
            if (poll != null) {
                insertLocalPoll(id, poll, publishedAt);
            }
            var mentions = new HashMap<UUID, Boolean>();
            mentionedIds(mentioned, authorId).forEach(mentionedId -> mentions.put(mentionedId, false));
            // A silent mention lets the quoted author see a followers-only quote, and its server be sent it.
            if (quoted != null && !quoted.actorId().equals(authorId)) {
                mentions.putIfAbsent(quoted.actorId(), true);
            }
            statuses.insertMentions(id, mentions);
            statuses.insertTags(id, Hashtags.names(linkable));
            emojis.insertForStatus(id, localEmojis);
            threads.assign(status, parent.map(Status::uri).orElse(null));
            if (quoted != null) {
                insertLocalQuote(status, quoted, quoting.local(), StatusQuote.requestUriFor(status.uri()));
            }
            events.publishEvent(new StatusStored(id));
            events.publishEvent(new StatusPublished(id, authorId));
            return Optional.of(status);
        });
    }

    /**
     * What a draft has once it's been checked, before mentions are looked up.
     */
    private record Prepared(String text, String spoilerText, String language, List<UUID> mediaIds, NewPoll poll,
                            Actor author, Quoting quoting, boolean localOnly) {
    }

    private Prepared prepare(UUID authorId, NewStatus draft) {
        var text = normalize(draft.text());
        // A content warning that can't be seen would hide nothing, but would still mark the status as sensitive.
        var spoilerText = looksBlank(normalize(draft.spoilerText())) ? "" : normalize(draft.spoilerText());
        var language = draft.language() == null || draft.language().isBlank() ? null : draft.language().strip();
        var mediaIds = checkMediaIds(draft.mediaIds());
        validate(text, spoilerText, language);
        if (mediaIds.isEmpty()) {
            requireText(text);
        }
        var poll = draft.poll() == null ? null : PollService.check(draft.poll());
        if (poll != null && !mediaIds.isEmpty()) {
            throw new InvalidStatusException(MEDIA_AND_POLL);
        }

        var author = actors.findLocalById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("The author isn't a local account"));
        // Before looking up mentions on other servers, so that a reply to nothing doesn't make those requests or use up the
        // author's lookups. The parent is checked again under a lock, since it can be deleted in between.
        var replyingTo = draft.inReplyToId() == null ? null
                : findVisible(draft.inReplyToId(), Optional.of(authorId)).orElseThrow(ReplyParentNotFoundException::new);
        var quoting = draft.quotedStatusId() == null ? null : quotable(draft.quotedStatusId(), authorId);
        // Replying to or quoting a local-only status keeps the conversation here, as on glitch-soc, so that its URI isn't
        // sent to another server in inReplyTo or quote.
        var localOnly = draft.localOnly()
                || (replyingTo != null && replyingTo.localOnly())
                || (quoting != null && quoting.status().localOnly());
        // Quoting another server's status would have to send this one to it, to ask for its approval.
        if (localOnly && quoting != null && !quoting.local()) {
            throw new InvalidStatusException(LOCAL_ONLY_REMOTE_QUOTE);
        }
        return new Prepared(text, spoilerText, language, mediaIds, poll, author, quoting, localOnly);
    }

    /**
     * Replaces the text of one of the author's own statuses, as on Mastodon: its visibility and parent stay as they were.
     * Mentions are looked up again, as for {@link #create}, and an account that the new text no longer mentions keeps the
     * status as a silent mention. Each earlier version is kept. An edit that changes nothing does nothing. Unlike on
     * Mastodon, an edit can change or remove what the status quotes, and a new quote is approved as one in a new status is.
     *
     * @return the status as it is now, or empty if the author has no status with this ID, or it's a boost
     * @throws InvalidStatusException        if the new version breaks a validation rule
     * @throws QuotedStatusNotFoundException if the status it's to quote doesn't exist or the author can't see it
     */
    public Optional<Status> edit(UUID id, UUID authorId, StatusEdit draft) {
        var text = normalize(draft.text());
        var spoilerText = looksBlank(normalize(draft.spoilerText())) ? "" : normalize(draft.spoilerText());
        var language = draft.language() == null || draft.language().isBlank() ? null : draft.language().strip();
        var newMediaIds = draft.mediaIds() == null ? null : checkMediaIds(draft.mediaIds());
        var newPoll = draft.poll() == null || draft.poll().poll() == null ? null : PollService.check(draft.poll().poll());
        validate(text, spoilerText, language);
        if (newMediaIds != null && newMediaIds.isEmpty()) {
            requireText(text);
        }
        var changes = new LinkedHashMap<UUID, MediaChange>();
        for (var change : draft.mediaChanges()) {
            changes.put(change.id(), new MediaChange(change.id(),
                    change.description() == null ? null : Objects.requireNonNullElse(
                            MediaAttachments.description(change.description()), ""), change.focus()));
        }

        var author = actors.findLocalById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("The author isn't a local account"));
        // Before looking up mentions, so that trying to edit someone else's status makes no requests. It's checked again
        // under a lock below.
        var editable = statuses.findById(id).filter(found -> isEditableBy(found, authorId));
        if (editable.isEmpty()) {
            return Optional.empty();
        }
        // Rendered before the lock below, so a concurrent edit that changes it is overwritten with what this rendered.
        var contentType = draft.contentType() == null ? editable.get().contentType() : draft.contentType();
        var quote = quotes.find(id).orElse(null);
        var quoteChange = isQuoteChange(draft.quote(), quote) ? draft.quote() : null;
        // Before looking up mentions, as for a new status, so that a quote that can't be made makes no requests.
        var requoting = quoteChange == null || quoteChange.quotedStatusId() == null ? null
                : requotable(editable.get(), quoteChange.quotedStatusId(), authorId);
        var linkable = linkable(text, contentType);
        var mentioned = resolveMentions(author, linkable);
        var rendered = render(text, contentType, mentioned);
        var content = quoteChange == null ? withQuoteFallback(rendered, text, quote)
                : requoting == null ? rendered
                : withQuoteFallback(rendered, text, requoting.status().link(), requoting.status().uri());

        var edited = transaction.execute(tx -> {
            var found = statuses.findByIdForEdit(id).filter(candidate -> isEditableBy(candidate, authorId));
            if (found.isEmpty()) {
                return new Edited(Optional.<Status>empty(), false);
            }
            var old = found.get();
            // Held so that it can't be deleted before the quote is linked to it, as for a new status.
            var requoted = requoting == null ? null : statuses.findByIdAndLock(requoting.status().id())
                    .filter(candidate -> isVisible(candidate, Optional.of(authorId)))
                    .orElseThrow(QuotedStatusNotFoundException::new);
            var oldQuote = quoteChange == null ? Optional.<StatusQuote>empty() : quotes.findAndLock(id);
            // Checked again, since a concurrent edit can have made the same change.
            var quoteChanged = quoteChange != null && (requoted == null ? oldQuote.isPresent()
                    : oldQuote.map(StatusQuote::quotedStatusId).filter(requoted.id()::equals).isEmpty());
            var oldPolicy = old.quoteApproval();
            var newPolicy = (draft.quotePolicy() == null && draft.quoteOthers() == null) || !old.visibility().isOpen()
                    ? oldPolicy : changedPolicy(oldPolicy, draft.quotePolicy(), draft.quoteOthers(),
                    () -> postingDefaults.quoteOthers(authorId));
            var policyChanged = !Objects.equals(oldPolicy, newPolicy);
            if (policyChanged) {
                quotes.setPolicy(id, newPolicy);
            }
            var newLanguage = language == null ? old.language() : language;
            var sensitive = (draft.sensitive() == null ? old.sensitive() : draft.sensitive()) || !spoilerText.isEmpty();
            var current = media.findByStatusAndLock(id);
            var currentIds = current.stream().map(MediaAttachment::id).toList();
            var mediaIds = newMediaIds == null ? currentIds : newMediaIds;
            if (mediaIds.isEmpty()) {
                requireText(text);
            }
            var oldPoll = polls.findAndLock(id);
            var pollEdit = PollEdit.of(oldPoll, draft.poll(), newPoll);
            if (pollEdit.hasPoll(oldPoll) && !mediaIds.isEmpty()) {
                throw new InvalidStatusException(MEDIA_AND_POLL);
            }
            var added = mediaIds.stream().filter(mediaId -> !currentIds.contains(mediaId)).toList();
            var claimed = media.findAttachableAndLock(added, authorId);
            if (claimed.size() != added.size()) {
                throw new InvalidStatusException("Media can't be found, or is already attached to a status");
            }
            var byId = new HashMap<UUID, MediaAttachment>();
            current.forEach(attachment -> byId.put(attachment.id(), attachment));
            claimed.forEach(attachment -> byId.put(attachment.id(), attachment));
            checkMediaTypes(mediaIds.stream().map(byId::get).map(MediaAttachment::type).toList());
            var describe = changes.values().stream()
                    .filter(change -> mediaIds.contains(change.id()))
                    .filter(change -> isChange(byId.get(change.id()), change))
                    .toList();
            // The content links each mention, so it changes whenever they do.
            var unchanged = text.equals(old.text()) && content.equals(old.content()) && contentType == old.contentType()
                    && spoilerText.equals(old.spoilerText()) && Objects.equals(newLanguage, old.language())
                    && sensitive == old.sensitive() && mediaIds.equals(currentIds) && describe.isEmpty()
                    && pollEdit == PollEdit.KEEP && !quoteChanged;
            if (unchanged) {
                if (!policyChanged) {
                    return new Edited(found, false);
                }
                touchNote(id, authorId);
                return new Edited(statuses.findById(id), false);
            }
            // In a later second than the version it replaces, even when that's in the future, since other servers are sent
            // whole seconds and ignore an edit that isn't later than what they have. That includes an Update sent for
            // something other than an edit.
            var since = Stream.of(old.publishedAt(), old.editedAt(), old.noteUpdatedAt())
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElseThrow();
            var next = since.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var editedAt = now.isBefore(next) ? next : now;
            if (!statuses.editLocal(id, authorId, text, content, contentType, spoilerText, newLanguage, sensitive,
                    editedAt)) {
                return new Edited(Optional.<Status>empty(), false);
            }
            switch (pollEdit) {
                case KEEP -> {
                }
                case REMOVE -> polls.delete(id);
                case REPLACE -> {
                    polls.delete(id);
                    insertLocalPoll(id, newPoll, now);
                }
                case HIDE_TOTALS -> polls.setHideTotals(id, newPoll.hideTotals());
            }
            var removed = !currentIds.stream().allMatch(mediaIds::contains);
            media.removeFromStatusExcept(id, mediaIds);
            media.attach(id, authorId, mediaIds);
            for (var change : describe) {
                var attachment = byId.get(change.id());
                media.updateDescription(change.id(),
                        change.description() == null ? attachment.description()
                                : change.description().isEmpty() ? null : change.description(),
                        change.focus() == null ? attachment.focus() : change.focus());
            }
            var addedMentions = statuses.replaceMentions(id, mentionedIds(mentioned, authorId));
            statuses.replaceTags(id, Hashtags.names(linkable));
            var pollOptions = switch (pollEdit) {
                case REMOVE -> List.<String>of();
                case REPLACE, HIDE_TOTALS -> newPoll.options();
                case KEEP -> oldPoll.map(PollRepository.Row::titles).orElse(List.of());
            };
            emojis.replaceForStatus(id, localEmojiIds(text, spoilerText, pollOptions));
            if (quoteChanged) {
                requote(old, oldQuote, requoted, requoting, now);
            }
            events.publishEvent(new StatusEdited(id, authorId, editedAt, addedMentions));
            return new Edited(statuses.findById(id), removed);
        });
        if (edited.removedMedia()) {
            attachments.processDeletions();
        }
        return edited.status();
    }

    private record Edited(Optional<Status> status, boolean removedMedia) {
    }

    private static final String MEDIA_AND_POLL = "A status can't have both media and a poll";
    private static final String LOCAL_ONLY_REMOTE_QUOTE = "A local-only status can't quote a status from another server";

    private void insertLocalPoll(UUID statusId, NewPoll poll, Instant from) {
        polls.insert(statusId, poll.multiple(), poll.hideTotals(), from.plusSeconds(poll.expiresIn()), null, null, null,
                poll.options(), null);
    }

    /**
     * What an edit does to a local status's poll. Changing its options, or whether more than one can be chosen, makes it a
     * new poll, as on Mastodon, which loses its votes and is open for as long as the edit says. Otherwise it keeps its
     * votes and when it closes, so that an edit of the text can't keep a poll open, and only whether its totals are hidden
     * can change.
     */
    private enum PollEdit {
        KEEP, REMOVE, REPLACE, HIDE_TOTALS;

        static PollEdit of(Optional<PollRepository.Row> old, PollChange change, NewPoll poll) {
            if (change == null) {
                return KEEP;
            }
            if (poll == null) {
                return old.isPresent() ? REMOVE : KEEP;
            }
            if (old.isEmpty() || !old.get().hasOptions(poll.options(), poll.multiple())) {
                return REPLACE;
            }
            return old.get().hideTotals() == poll.hideTotals() ? KEEP : HIDE_TOTALS;
        }

        boolean hasPoll(Optional<PollRepository.Row> old) {
            return this == KEEP ? old.isPresent() : this != REMOVE;
        }
    }

    /**
     * A status from before quotes keeps having only its author able to quote it, unless the edit says otherwise, and is
     * treated as {@link QuotePolicy#NOBODY} when it does. One whose quotes are all approved automatically doesn't keep
     * what would happen to anyone else's, so a change that doesn't say uses the author's default for that.
     * <p>
     * A status from before {@link OtherQuotes} that only reviewed its author's followers' quotes reads back as
     * {@link QuotePolicy#NOBODY} with {@link OtherQuotes#REVIEW}, so a change to it lets everyone else ask too. Its stored
     * approval isn't converted, since other servers were sent it, and it decides as before until it's changed.
     *
     * @param policy   whose quotes of it are approved automatically, or null to keep that
     * @param others   what happens to everyone else's, or null to keep that
     * @param fallback the author's default for everyone else's
     */
    static QuoteApproval changedPolicy(
            QuoteApproval old, QuotePolicy policy, OtherQuotes others, Supplier<OtherQuotes> fallback) {
        if (old == null && policy == null && others == null) {
            return null;
        }
        OtherQuotes rest;
        if (others != null) {
            rest = others;
        } else if (old != null && old.policy() != QuotePolicy.PUBLIC) {
            rest = old.others();
        } else {
            rest = fallback.get();
        }
        var automatic = policy != null ? policy : old != null ? old.policy() : QuotePolicy.NOBODY;
        return automatic.approval(rest);
    }

    /**
     * Naming what it quotes already keeps the quote, and its approval, and so does removing a quote it doesn't have.
     */
    private static boolean isQuoteChange(QuoteChange change, StatusQuote quote) {
        if (change == null) {
            return false;
        }
        if (quote == null) {
            return change.quotedStatusId() != null;
        }
        // A quote whose quoted status is gone has no ID to compare, and can still be removed.
        return change.quotedStatusId() == null || !change.quotedStatusId().equals(quote.quotedStatusId());
    }

    /**
     * A status that an edit can make this one quote. A new status would be narrowed to followers-only, or to local-only,
     * by what it quotes, but an edit can't change either, since the status has been sent already.
     *
     * @throws QuotedStatusNotFoundException if it can't be found, or the author can't see it
     * @throws InvalidStatusException        if it can't be quoted, or not by this status
     */
    private Quoting requotable(Status status, UUID quotedId, UUID authorId) {
        if (quotedId.equals(status.id())) {
            throw new InvalidStatusException("A status can't quote itself");
        }
        var quoting = quotable(quotedId, authorId);
        if (quoting.status().visibility() == Visibility.PRIVATE && status.visibility().isOpen()) {
            throw new InvalidStatusException("Only a followers-only or direct status can quote a followers-only status");
        }
        if (status.localOnly() && !quoting.local()) {
            throw new InvalidStatusException(LOCAL_ONLY_REMOTE_QUOTE);
        }
        if (!status.localOnly() && quoting.status().localOnly()) {
            throw new InvalidStatusException("Only a local-only status can quote a local-only status");
        }
        return quoting;
    }

    /**
     * Replaces or removes what a status quotes, in the transaction of the edit. The quoted author keeps their silent
     * mention, as anyone an edit stops mentioning does, so that their server is sent the {@code Update} that drops it.
     *
     * @param quoted    the status it's to quote, locked, or null to remove the quote
     * @param requoting what {@link #requotable} found of it, or null
     */
    private void requote(Status status, Optional<StatusQuote> old, Status quoted, Quoting requoting, Instant now) {
        old.ifPresent(found -> {
            quotes.delete(status.id());
            if (found.quotedAccountId() != null && !found.quotedAccountId().equals(status.actorId())
                    && actors.isLocal(found.quotedAccountId())) {
                events.publishEvent(new QuoteRemoved(status.id(), status.actorId(), found.quotedAccountId()));
            }
        });
        if (quoted == null) {
            return;
        }
        if (!quoted.actorId().equals(status.actorId())) {
            statuses.insertMentions(status.id(), Map.of(quoted.actorId(), true));
        }
        // A new ID, so that a late answer to a request for what it quoted before can't decide this, and a server that
        // answered the status's first request isn't asked again under the same ID.
        insertLocalQuote(status, quoted, requoting.local(), StatusQuote.requestUriFor(status.uri(), now));
    }

    /**
     * A status that the author is allowed to quote.
     *
     * @param local whether its author is this server's
     */
    private record Quoting(Status status, boolean local) {
    }

    /**
     * @throws QuotedStatusNotFoundException if it can't be found, or the author can't see it
     * @throws InvalidStatusException        if its author doesn't allow this author to quote it
     */
    private Quoting quotable(UUID quotedId, UUID authorId) {
        var quoted = findVisible(quotedId, Optional.of(authorId)).orElseThrow(QuotedStatusNotFoundException::new);
        var local = actors.isLocal(quoted.actorId());
        var decision = quoteRules.decide(quoted, quoted.quoteApproval(), local, authorId);
        if (decision == QuoteDecision.DENIED) {
            throw new InvalidStatusException("This status can't be quoted");
        }
        return new Quoting(quoted, local);
    }

    /**
     * A quote of a local status is decided here and now, unless its author approves quotes by hand. One of a remote status
     * is approved by its author's server, unless its server can't be asked, which a status with no policy says.
     *
     * @param requestUri the {@code QuoteRequest} to send for a quote of a remote status
     */
    private void insertLocalQuote(Status status, Status quoted, boolean local, URI requestUri) {
        var self = quoted.actorId().equals(status.actorId());
        var target = Optional.of(new StatusRepository.Parent(quoted.id(), quoted.actorId()));
        if (local && !self
                && quoteRules.decide(quoted, quoted.quoteApproval(), true, status.actorId()) == QuoteDecision.MANUAL) {
            quotes.insert(status.id(), quoted.uri(), target, QuoteState.PENDING, false, null, null, null, Instant.now());
            events.publishEvent(new QuoteReviewRequested(status.id(), status.actorId(), quoted.actorId()));
            return;
        }
        if (local || self) {
            quotes.insert(status.id(), quoted.uri(), target, QuoteState.ACCEPTED, false, null,
                    self ? null : Ids.timeOrdered(Instant.now()), null, null);
            publishQuoteAccepted(status, quoted.actorId());
            return;
        }
        var legacy = quoted.quoteApproval() == null;
        quotes.insert(status.id(), quoted.uri(), target, legacy ? QuoteState.ACCEPTED : QuoteState.PENDING, legacy, null,
                null, requestUri, Instant.now());
        events.publishEvent(new QuoteRequested(status.id(), status.actorId()));
    }

    /**
     * Quoting oneself tells nobody anything.
     */
    void publishQuoteAccepted(Status quoting, UUID quotedAuthorId) {
        if (!quoting.actorId().equals(quotedAuthorId)) {
            events.publishEvent(new QuoteAccepted(quoting.id(), quoting.actorId(), quotedAuthorId));
        }
    }

    void touchNote(UUID statusId, UUID authorId) {
        quotes.touchNote(statusId).ifPresent(at -> events.publishEvent(new StatusNoteChanged(statusId, authorId, at)));
    }

    /**
     * Adds a link to the quoted status for servers and clients that don't show quotes, as Mastodon does, unless the text
     * links to it already. Its class lets clients that do show quotes hide it.
     */
    private static String withQuoteFallback(String content, String text, URI link, URI uri) {
        if (text.contains(link.toString()) || text.contains(uri.toString())) {
            return content;
        }
        return PlainText.quoteInline(link) + content;
    }

    /**
     * The fallback for what a status quotes, which is kept through edits that keep the quote.
     */
    private String withQuoteFallback(String content, String text, StatusQuote quote) {
        if (quote == null) {
            return content;
        }
        var link = Optional.ofNullable(quote.quotedStatusId()).flatMap(statuses::findById).map(Status::link)
                .orElse(quote.quotedUri());
        return withQuoteFallback(content, text, link, quote.quotedUri());
    }

    /**
     * @param change a description that's empty to remove it, or null to keep it
     */
    private static boolean isChange(MediaAttachment attachment, MediaChange change) {
        var description = change.description() == null ? attachment.description()
                : change.description().isEmpty() ? null : change.description();
        var focus = change.focus() == null ? attachment.focus() : change.focus();
        return !Objects.equals(description, attachment.description()) || !Objects.equals(focus, attachment.focus());
    }

    /**
     * @throws InvalidStatusException if there are too many, or one is given twice
     */
    private static List<UUID> checkMediaIds(List<UUID> ids) {
        if (ids.size() > MediaAttachments.MAX_PER_STATUS) {
            throw new InvalidStatusException(
                    "A status can't have more than %d attachments".formatted(MediaAttachments.MAX_PER_STATUS));
        }
        if (Set.copyOf(ids).size() != ids.size()) {
            throw new InvalidStatusException("The same media can't be attached twice");
        }
        return ids;
    }

    /**
     * Locks the author's uploads until the transaction ends, so that no other status can have them.
     *
     * @return the IDs, in order
     * @throws InvalidStatusException if one isn't the author's, is attached already, or has expired, or they can't go
     *                                together
     */
    List<UUID> claimMedia(List<UUID> ids, UUID authorId) {
        if (ids.isEmpty()) {
            return ids;
        }
        var found = media.findAttachableAndLock(ids, authorId);
        if (found.size() != ids.size()) {
            throw new InvalidStatusException("Media can't be found, or is already attached to a status");
        }
        checkMediaTypes(found.stream().map(MediaAttachment::type).toList());
        return ids;
    }

    /**
     * As on Mastodon, whose clients lay out either images, or one video or audio.
     */
    private static void checkMediaTypes(List<AttachmentType> types) {
        var playable = types.stream().filter(type -> type == AttachmentType.VIDEO || type == AttachmentType.AUDIO).count();
        if (playable > 1 || (playable == 1 && types.size() > 1)) {
            throw new InvalidStatusException("A video or audio can't be attached with any other media");
        }
    }

    private static boolean isEditableBy(Status status, UUID authorId) {
        return status.actorId().equals(authorId) && !status.isReblog();
    }

    /**
     * Each version of a status, oldest first, ending with the one it has now. A status from another server only has the
     * versions this server was sent.
     */
    public List<StatusVersion> findHistory(Status status) {
        var versions = new ArrayList<>(statuses.findEdits(status.id()));
        var current = emojis.findForStatus(status.id()).stream().map(CustomEmoji::id).toList();
        versions.add(StatusVersion.current(
                status, polls.find(status.id()).map(PollRepository.Row::titles).orElse(null), current));
        return List.copyOf(versions);
    }

    /**
     * Every account a status mentions, silently or not, including those that are suspended, which is who it was sent to
     * besides followers.
     */
    public List<UUID> findAllMentioned(UUID statusId) {
        return statuses.findAllMentioned(statusId);
    }

    /**
     * The local accounts that boosted a status, whose followers can have it from their boosts.
     */
    public List<UUID> findLocalBoosters(UUID statusId) {
        return boosts.findLocal(statusId).stream().map(StatusBoost::actorId).toList();
    }

    private String render(String text, ContentType contentType, Map<String, Actor> mentioned) {
        if (text.isEmpty()) {
            return "";
        }
        Function<Mentions.Match, PlainText.MentionLink> links = match -> Optional.ofNullable(mentioned.get(acct(match)))
                .map(actor -> new PlainText.MentionLink(actor.uri(), actor.username() + "@" + actor.domain()))
                .orElse(null);
        return switch (contentType) {
            case PLAIN -> PlainText.render(text, links, instance.baseUrl());
            case MARKDOWN -> Markdown.render(text, links, instance.baseUrl());
        };
    }

    /**
     * The text that mentions and hashtags are found in: only where they're linked, so that one in Markdown's code
     * doesn't notify anyone or tag the status.
     */
    private static String linkable(String text, ContentType contentType) {
        return contentType == ContentType.MARKDOWN ? Markdown.linkableText(text) : text;
    }

    /**
     * An author mentioning themselves isn't stored as a mention, as on Mastodon.
     */
    private static List<UUID> mentionedIds(Map<String, Actor> mentioned, UUID authorId) {
        return mentioned.values().stream()
                .map(Actor::id)
                .filter(mentionedId -> !mentionedId.equals(authorId))
                .distinct()
                .toList();
    }

    /**
     * Finds the account each mention in a status names. Each address is looked up once, however often it's mentioned.
     *
     * @return the accounts found, under {@link #acct}
     */
    private Map<String, Actor> resolveMentions(Actor author, String text) {
        var found = new LinkedHashMap<String, Optional<Actor>>();
        for (var match : Mentions.find(text)) {
            found.computeIfAbsent(acct(match), key -> resolveMention(author, match));
        }
        var resolved = new LinkedHashMap<String, Actor>();
        found.forEach((key, actor) -> actor.ifPresent(value -> resolved.put(key, value)));
        return resolved;
    }

    private Optional<Actor> resolveMention(Actor author, Mentions.Match match) {
        var domain = match.domain() == null ? null : match.domain().toLowerCase(Locale.ROOT);
        if (domain == null || instance.isLocalDomain(domain)) {
            return actors.findLocalByUsername(match.username());
        }
        try {
            return resolver.resolveAcct(author.id(), match.username(), domain);
        } catch (RemoteActorResolver.ResolveLimitException e) {
            return resolver.findStored(match.username(), domain);
        }
    }

    /**
     * Usernames and domains are both matched without regard to case.
     */
    private String acct(Mentions.Match match) {
        var domain = match.domain() == null || instance.isLocalDomain(match.domain())
                ? instance.accountDomain()
                : match.domain();
        return (match.username() + "@" + domain).toLowerCase(Locale.ROOT);
    }

    /**
     * The accounts each status shows as mentioned, leaving out silent mentions and accounts that are suspended or deleted.
     *
     * @return an entry for each status that mentions anyone
     */
    public Map<UUID, List<Actor>> findMentions(Collection<UUID> statusIds) {
        var mentions = statuses.findMentions(statusIds);
        var actorsById = actors.findByIds(mentions.values().stream().flatMap(List::stream).distinct().toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var result = new HashMap<UUID, List<Actor>>();
        mentions.forEach((statusId, actorIds) -> result.put(
                statusId, actorIds.stream().map(actorsById::get).filter(Objects::nonNull).toList()));
        return result;
    }

    /**
     * Whether this is a language code that statuses can have, such as {@code en} or {@code pt-BR}.
     */
    public static boolean isLanguageCode(String value) {
        return value != null && LANGUAGE.matcher(value).matches();
    }

    /** Batch visibility lookup for notifications and conversation summaries. */
    public List<Status> findVisible(Collection<UUID> ids, UUID viewer) {
        if (ids.isEmpty()) return List.of();
        var found = statuses.findByIds(ids);
        // Follows and mentions only matter for what isVisibleTo doesn't already show, and most statuses are open.
        var hidden = found.stream().filter(status -> !status.isVisibleTo(Optional.of(viewer))).toList();
        Set<UUID> followed = hidden.stream().anyMatch(status -> status.visibility() == Visibility.PRIVATE)
                ? Set.copyOf(follows.followedIds(viewer)) : Set.of();
        Set<UUID> mentioned = hidden.isEmpty() ? Set.of() : statuses.mentioning(hidden.stream().map(Status::id).toList(), viewer);
        var refusing = blocks.refusing(viewer, found.stream().map(Status::actorId).collect(Collectors.toSet()));
        var local = found.stream().anyMatch(Status::localOnly) && actors.isLocal(viewer);
        return found.stream()
                .filter(status -> isVisible(status, Optional.of(viewer), followed::contains, mentioned::contains,
                        refusing::contains, local))
                .toList();
    }

    /**
     * The statuses that anyone can see, for a viewer who isn't signed in, such as on web pages.
     */
    public List<Status> findVisibleToAnyone(Collection<UUID> ids) {
        return statuses.findByIds(ids).stream().filter(status -> status.isVisibleTo(Optional.empty())).toList();
    }

    /**
     * Finds a status whoever can see it, if its author is active, for what has to check who can.
     */
    public Optional<Status> findStored(UUID id) {
        return statuses.findById(id);
    }

    /**
     * Like {@link #findStored(UUID)}, for several at once.
     */
    public List<Status> findStored(Collection<UUID> ids) {
        return statuses.findByIds(ids);
    }

    /**
     * Finds an author's status by its URI, whoever can see it, if the author is active.
     */
    public Optional<Status> findStoredByUri(URI uri, UUID authorId) {
        return statuses.findByUriForAuthor(uri, authorId);
    }

    /**
     * Finds a status by its URI, or by the page its server gave, whoever can see it, if its author is active.
     */
    public Optional<Status> findStoredByUrl(String uriOrUrl) {
        return statuses.findByUriOrUrl(uriOrUrl);
    }

    /** Finds a status if this viewer is allowed to see it. */
    public Optional<Status> findVisible(UUID id, Optional<UUID> viewer) {
        return statuses.findById(id).filter(status -> isVisible(status, viewer));
    }

    /**
     * Whether this viewer is allowed to see a status. Besides what {@link Status#isVisibleTo} allows, a followers-only status
     * can be seen by an account that follows its author, and a followers-only or direct status by the accounts it mentions,
     * as on Mastodon. A local-only status can only be seen by accounts here, as one that's open or followers-only would be.
     * Nothing by an author who blocks the viewer, or the viewer's server, can be seen, so that they can't favourite, boost or
     * reply to it either.
     */
    public boolean isVisible(Status status, Optional<UUID> viewer) {
        return isVisible(
                status,
                viewer,
                author -> viewer.filter(id -> follows.isFollowing(id, author)).isPresent(),
                statusId -> viewer.filter(id -> !statuses.mentioning(List.of(statusId), id).isEmpty()).isPresent(),
                author -> viewer.filter(id -> !blocks.refusing(id, List.of(author)).isEmpty()).isPresent(),
                status.localOnly() && viewer.filter(actors::isLocal).isPresent());
    }

    /**
     * Finds a status if this actor, fetching it from another server, is allowed to see it. Unlike {@link #isVisible}, a
     * followers-only status is only shown to the accounts it mentions, or mentioned before an edit, and to current followers
     * who were sent it, or who were already following when it was posted, for one from before that was recorded. Those are
     * who its {@code Update}s are sent to, and its {@code Delete} reaches all of them, so a fetch tells nobody else that it
     * exists, and nobody can have it without being sent its deletion. Former followers aren't shown it, as they aren't sent
     * its edits.
     *
     * <p>An author who blocks the fetcher, or the fetcher's server, doesn't show it anything. A local-only status is shown
     * to no other server.
     *
     * @param fetcher an active actor, or empty for a fetch that nobody is known to be behind
     */
    public Optional<Status> findFetchable(UUID id, Optional<UUID> fetcher) {
        return statuses.findById(id).filter(status -> {
            if (status.localOnly()) {
                return false;
            }
            if (fetcher.isPresent() && !blocks.refusing(fetcher.get(), List.of(status.actorId())).isEmpty()) {
                return false;
            }
            if (status.isVisibleTo(fetcher)) {
                return true;
            }
            if (fetcher.isEmpty()) {
                return false;
            }
            // Silent mentions too, which an edit leaves the accounts it no longer mentions, so they keep what they were sent.
            var mentioned = statuses.findAllMentioned(id).contains(fetcher.get());
            if (mentioned || status.visibility() != Visibility.PRIVATE) {
                return mentioned;
            }
            return follows.wasFollowingAsOf(fetcher.get(), status.actorId(), status.publishedAt())
                    || (follows.isFollowing(fetcher.get(), status.actorId()) && followersOnly.wasSent(id, fetcher.get()));
        });
    }

    /**
     * @param followsAuthor  whether the viewer follows an author, for a caller that has looked that up already
     * @param mentionsViewer whether a status mentions the viewer, for a caller that has looked that up already
     * @param refusesViewer  whether an author blocks the viewer or the viewer's server, for a caller that has looked that up
     *                       already
     * @param viewerIsLocal  whether the viewer is an account here, which only has to be right for a local-only status
     */
    private static boolean isVisible(
            Status status, Optional<UUID> viewer, Predicate<UUID> followsAuthor, Predicate<UUID> mentionsViewer,
            Predicate<UUID> refusesViewer, boolean viewerIsLocal) {
        if (viewer.isPresent() && refusesViewer.test(status.actorId())) {
            return false;
        }
        if (status.isVisibleTo(viewer)) {
            return true;
        }
        if (viewer.isEmpty() || (status.localOnly() && !viewerIsLocal)) {
            return false;
        }
        if (status.visibility().isOpen()) {
            return true;
        }
        return (status.visibility() == Visibility.PRIVATE && followsAuthor.test(status.actorId()))
                || mentionsViewer.test(status.id());
    }

    /**
     * Finds the statuses around one in its thread that this viewer is allowed to see, as Mastodon's context does, with the
     * same limits as Mastodon has for a viewer who isn't signed in. Only what's stored here is listed. Statuses by accounts
     * the viewer has blocked, muted, or blocked the server of, or that block the viewer, are left out, but the status itself
     * is shown, since the viewer asked for it.
     *
     * @return empty if the viewer can't see the status itself
     */
    public Optional<StatusContext> findContext(UUID id, Optional<UUID> viewer) {
        return findVisible(id, viewer).map(status -> {
            var ancestors = statuses.findAncestors(id, MAX_ANCESTORS);
            var descendants = statuses.findDescendants(id, MAX_DESCENDANT_DEPTH, MAX_DESCENDANTS);
            // One lookup of each kind for the whole thread, rather than one for each followers-only or direct status in it.
            var hidden = viewer.isEmpty() ? List.<Status>of() : Stream.concat(ancestors.stream(), descendants.stream())
                    .filter(found -> !found.isVisibleTo(viewer))
                    .toList();
            var needsFollows = hidden.stream().anyMatch(found -> found.visibility() == Visibility.PRIVATE);
            Set<UUID> followed = needsFollows ? Set.copyOf(follows.followedIds(viewer.get())) : Set.of();
            Set<UUID> mentioning = hidden.isEmpty()
                    ? Set.of()
                    : statuses.mentioning(hidden.stream().map(Status::id).toList(), viewer.get());
            var authors = Stream.concat(ancestors.stream(), descendants.stream()).map(Status::actorId)
                    .collect(Collectors.toSet());
            Set<UUID> hiddenAuthors = viewer.isEmpty() ? Set.of() : blocks.hiddenFrom(viewer.get(), authors);
            var local = hidden.stream().anyMatch(Status::localOnly) && actors.isLocal(viewer.get());
            Predicate<Status> visible = found -> !hiddenAuthors.contains(found.actorId())
                    && isVisible(found, viewer, followed::contains, mentioning::contains, hiddenAuthors::contains, local);
            return new StatusContext(
                    ancestors.stream().filter(visible).toList(), descendants.stream().filter(visible).toList());
        });
    }

    /**
     * Finds where a status's thread starts among what's stored, for fetching the parents it's missing. That's the status
     * itself if its parent isn't stored.
     *
     * @return empty if the status isn't stored, or as many ancestors as {@link #findContext} lists are stored already
     */
    public Optional<Status> findEarliestAncestor(UUID id) {
        return statuses.findById(id).flatMap(status -> {
            var ancestors = statuses.findAncestors(id, MAX_ANCESTORS);
            if (ancestors.size() >= MAX_ANCESTORS) {
                return Optional.empty();
            }
            return Optional.of(ancestors.isEmpty() ? status : ancestors.getFirst());
        });
    }

    /**
     * Whether as many of a status's replies, and theirs, are stored as {@link #findContext} lists, whoever can see them,
     * so that fetching more would show nothing new.
     */
    public boolean hasFullThread(UUID id) {
        return statuses.countDescendants(id, MAX_DESCENDANT_DEPTH, MAX_DESCENDANTS) >= MAX_DESCENDANTS;
    }

    /**
     * Finds one of the author's own statuses, and stops it from being deleted until the transaction ends. Whatever is queued
     * for the status in this transaction is then there to be found by the listeners for {@link StatusDeleted}.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Status> findAndHold(UUID id, UUID authorId) {
        return statuses.findByIdAndLock(id).filter(status -> status.actorId().equals(authorId));
    }

    /**
     * Like {@link #findAndHold}, but also stops the status from being edited until the transaction ends, for a listener that
     * has to see its latest version before a later edit's listener does.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException if there's no transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Status> findAndHoldAgainstEdits(UUID id, UUID authorId) {
        return statuses.findByIdForEdit(id).filter(status -> status.actorId().equals(authorId));
    }

    /**
     * Deletes one of the author's own statuses. A tombstone is only kept for a status that anyone could see, because
     * others get the same answer for a followers-only or direct status as for one that never existed, and a tombstone would
     * reveal that it had.
     *
     * <p>Its attachments are kept for {@link AttachmentProperties#redraftRetention}, as on Mastodon, so that its author
     * can post them again, unless {@code deleteMedia} is set.
     *
     * @return the deleted status, or empty if the author has no status with this ID
     */
    public Optional<Status> delete(UUID id, UUID authorId, boolean deleteMedia) {
        var result = transaction.execute(tx -> {
            // Locked first, so that a boost made meanwhile is either found here or fails to find the status.
            if (statuses.findForDelete(id).filter(status -> status.actorId().equals(authorId)).isEmpty()) {
                return Optional.<Status>empty();
            }
            return deleteLocked(id, authorId, deleteMedia, tx);
        });
        if (deleteMedia && result.isPresent()) {
            attachments.processDeletions();
        }
        return result;
    }

    /**
     * Deletes a local status that's already been locked with {@link StatusRepository#findForDelete}.
     */
    private Optional<Status> deleteLocked(UUID id, UUID authorId, boolean deleteMedia, TransactionStatus tx) {
        // Before the status goes, and its mentions and boosts with it.
        var mentioned = statuses.findAllMentioned(id);
        var localBoosts = boosts.findLocal(id);
        if (deleteMedia) {
            media.removeFromStatusExcept(id, List.of());
        } else {
            media.keepForRedraft(id, Instant.now().plus(attachmentProperties.redraftRetention()));
        }
        var deleted = statuses.delete(id, authorId);
        if (deleted.isEmpty()) {
            tx.setRollbackOnly();
        }
        deleted.ifPresent(status -> {
            if (status.isVisibleTo(Optional.empty())) {
                statuses.insertTombstone(status, Instant.now().truncatedTo(ChronoUnit.MICROS));
            }
            var boosters = localBoosts.stream().map(boost -> {
                events.publishEvent(StatusUnreblogged.of(boost, status.uri(), authorId));
                return boost.actorId();
            }).toList();
            events.publishEvent(new StatusDeleted(
                    status.id(), status.uri(), authorId, status.visibility(), status.publishedAt(),
                    shownParentAuthor(status), mentioned, boosters, status.localOnly()));
        });
        return deleted;
    }

    /**
     * Like {@link #delete(UUID, UUID, boolean)}, keeping its attachments for a while.
     */
    public Optional<Status> delete(UUID id, UUID authorId) {
        return delete(id, authorId, false);
    }

    /**
     * The parent's author, if the parent is still one that anyone can see. That's when a public or unlisted reply's
     * {@code Note} names its parent, so it's who was sent the reply. A parent's visibility can't change. One that's gone since
     * was either this server's, whose author isn't sent anything, or removed with its author's account or server, which
     * nothing is sent to any more.
     */
    private UUID shownParentAuthor(Status status) {
        return Optional.ofNullable(status.inReplyToId())
                .flatMap(statuses::findById)
                .filter(parent -> parent.isVisibleTo(Optional.empty()))
                .map(Status::actorId)
                .orElse(null);
    }

    /**
     * Finds a status for an admin to remove by its ID, its URI, or the page its server gave. This includes the statuses of
     * suspended authors, which can come back, so check what's shown: it doesn't make a followers-only or direct status
     * visible to anyone who couldn't see it before.
     */
    public Optional<Status> findForModeration(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        var trimmed = query.strip();
        var id = Ids.parse(trimmed);
        return id.isPresent() ? id.flatMap(statuses::findForModeration) : statuses.findForModeration(trimmed);
    }

    /**
     * Finds reported statuses for the admins handling a report, whoever can see them. Reports are the one place admins read
     * followers-only and direct statuses, since whoever reported one has shared it with them on purpose. Includes the
     * statuses of suspended authors, which can come back.
     */
    public List<Status> findForReport(Collection<UUID> ids) {
        return statuses.findForModeration(ids);
    }

    /**
     * Removes a status for breaking the rules, and remembers which admin did. A local status is deleted as its author would
     * have, attachments and all, so its {@code Delete} goes to the same servers. A remote status is only removed from this
     * server, since this one can't speak for it, and is never stored again, because its server never says it's gone and
     * can send it again at any time.
     *
     * @return the removed status, or empty if there's none with this ID, or it's gone meanwhile
     */
    public Optional<Status> remove(UUID id, UUID adminId) {
        var found = statuses.findForModeration(id);
        var author = found.flatMap(status -> actors.findById(status.actorId())).filter(actor -> !actor.isDeleted());
        if (author.isEmpty()) {
            return Optional.empty();
        }
        var status = found.get();
        var local = actors.isLocal(author.get());
        var removed = transaction.execute(tx -> {
            if (local) {
                if (statuses.findForDelete(id).isEmpty()) {
                    return Optional.<Status>empty();
                }
                var deleted = deleteLocked(id, status.actorId(), true, tx);
                deleted.ifPresent(gone ->
                        statuses.insertRemoval(gone.uri(), gone.actorId(), author.get().uri(), true, adminId));
                return deleted;
            }
            // Before the row is locked, in the same order as when its server deletes it, which can happen meanwhile.
            statuses.lockUri(status.uri());
            // Even if its server deleted it meanwhile, since that's only remembered for a while, and it could be sent again.
            statuses.insertRemoval(status.uri(), status.actorId(), author.get().uri(), false, adminId);
            return remoteStatuses.deleteLocked(status.uri(), author.get().uri()) ? Optional.of(status) : Optional.<Status>empty();
        });
        if (removed.isPresent()) {
            attachments.processDeletions();
        }
        return removed;
    }

    /**
     * Removes all of an account's statuses, for when it's being deleted. No {@link StatusDeleted} is published for them,
     * because other servers are told about the account instead, which covers its statuses.
     */
    public void removeAllBy(UUID authorId) {
        statuses.removeAllBy(authorId);
        threads.removeForActor(authorId);
        attachments.removeOrphans();
    }

    /**
     * Removes the statuses of every remote actor on a domain, for when it's blocked. Each batch commits by itself.
     *
     * @param domain a domain as {@code actor.domain} has it
     */
    public void removeForDomain(String domain) {
        statuses.removeForDomain(domain);
        threads.removeForDomain(domain);
        attachments.removeOrphans();
    }

    /**
     * Finds what's left of a deleted status of this author's, if it's one that anyone could see.
     */
    public Optional<DeletedStatus> findTombstone(UUID id, UUID authorId) {
        return statuses.findTombstone(id, authorId);
    }

    /**
     * The names of each status's hashtags, in no particular order.
     *
     * @return an entry for each status that has any
     */
    public Map<UUID, List<String>> findTags(Collection<UUID> statusIds) {
        return statuses.findTags(statusIds);
    }

    /**
     * This server's emoji that a local status names, in its text, its content warning, or its poll's options.
     */
    private List<UUID> localEmojiIds(String text, String spoilerText, List<String> pollOptions) {
        var texts = new ArrayList<String>();
        texts.add(text);
        texts.add(spoilerText);
        texts.addAll(pollOptions);
        return emojis.findLocalIn(texts).values().stream().map(CustomEmoji::id).toList();
    }

    /**
     * Lists the statuses of one account that this viewer is allowed to see, which includes those that mention them, and
     * its boosts unless they're excluded.
     */
    public List<Status> findByAccount(
            UUID actorId, Optional<UUID> viewer, boolean excludeReplies, boolean excludeReblogs, IdPage page) {
        return findByAccount(actorId, viewer, excludeReplies, excludeReblogs, false, Optional.empty(), page);
    }

    /**
     * <p>An account that blocks the viewer, or the viewer's server, shows them nothing, and boosts of statuses hidden from
     * the viewer are left out, as on Mastodon.
     *
     * @param onlyMedia whether to only list statuses with attachments, which leaves out boosts
     * @param tag       a normalized name, to only list statuses with that hashtag, which leaves out boosts
     */
    public List<Status> findByAccount(
            UUID actorId, Optional<UUID> viewer, boolean excludeReplies, boolean excludeReblogs, boolean onlyMedia,
            Optional<String> tag, IdPage page) {
        if (viewer.isPresent() && !blocks.refusing(viewer.get(), List.of(actorId)).isEmpty()) {
            return List.of();
        }
        var visibilities = viewer.filter(actorId::equals).isPresent()
                ? Set.of(Visibility.values())
                : viewer.filter(id -> follows.isFollowing(id, actorId)).isPresent()
                ? Set.of(Visibility.PUBLIC, Visibility.UNLISTED, Visibility.PRIVATE)
                : Set.of(Visibility.PUBLIC, Visibility.UNLISTED);
        return statuses.findByAccount(actorId, visibilities, viewer.orElse(null), excludeReplies, excludeReblogs,
                onlyMedia, tag.orElse(null), page);
    }

    /**
     * Counts what {@link #findByAccount} lists for a viewer who isn't signed in, boosts and replies included.
     */
    public long countVisibleToAnyone(UUID actorId) {
        return statuses.countByAccount(actorId, Set.of(Visibility.PUBLIC, Visibility.UNLISTED));
    }

    /**
     * @return an entry for each given account
     */
    public Map<UUID, AccountStats> statsFor(Collection<UUID> actorIds) {
        var stats = new HashMap<>(statuses.statsByAccount(actorIds));
        actorIds.forEach(actorId -> stats.putIfAbsent(actorId, AccountStats.EMPTY));
        return stats;
    }

    /**
     * What each URL counts as toward the length limit.
     */
    public int charactersReservedPerUrl() {
        return Urls.CHARACTERS_RESERVED_PER_URL;
    }

    /**
     * The most characters a new status can have stored, which is more than the length limit because URLs and the domains in
     * mentions count as fewer characters than they have.
     */
    public long maxStoredCharacters() {
        return (long) properties.maxCharacters() + Urls.MAX_URL_SAVINGS + Mentions.MAX_DOMAIN_SAVINGS;
    }

    /**
     * Counts the statuses of local accounts, leaving out direct ones.
     */
    public long countLocal() {
        return statuses.countLocalExcludingDirect();
    }

    /**
     * Counts the statuses local accounts published in each week since {@code since}, keyed by the Monday the week starts
     * on, in UTC. Like {@link #countLocal}, this leaves out direct statuses, and it leaves out boosts too. Deleted statuses,
     * and those of suspended accounts, aren't counted.
     */
    public Map<LocalDate, Long> countLocalByWeek(LocalDate since) {
        return statuses.countLocalByWeek(since);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private void validate(String text, String spoilerText, String language) {
        requireStorable(text);
        requireStorable(spoilerText);
        var length = Urls.countCharacters(text) + spoilerText.codePointCount(0, spoilerText.length());
        if (length > properties.maxCharacters()) {
            throw new InvalidStatusException(
                    "Text can't be longer than %d characters".formatted(properties.maxCharacters()));
        }
        if (language != null && !LANGUAGE.matcher(language).matches()) {
            throw new InvalidStatusException("Language is not a valid language code");
        }
    }

    /**
     * A status with media can have no text, as on Mastodon.
     */
    private static void requireText(String text) {
        if (looksBlank(text)) {
            throw new InvalidStatusException("Text can't be blank");
        }
    }

    /**
     * Whether text would show nothing. {@link String#strip} only removes some kinds of space, so this also covers
     * no-break spaces, invisible formatting characters such as zero-width spaces, and the letters and symbols that are
     * drawn as blanks.
     */
    private static boolean looksBlank(String value) {
        return value.codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT
                || BLANK_CHARACTERS.contains(codePoint));
    }

    /**
     * PostgreSQL rejects NUL, which would be a server error, and an unpaired surrogate would silently be stored as "?".
     */
    private static void requireStorable(String value) {
        if (!isStorable(value)) {
            throw new InvalidStatusException("Text contains invalid characters");
        }
    }

    static boolean isStorable(String value) {
        for (var i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                return false;
            }
        }
        return true;
    }
}
