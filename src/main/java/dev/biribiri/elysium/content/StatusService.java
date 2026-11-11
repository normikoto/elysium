package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.internal.BoostRepository;
import dev.biribiri.elysium.content.internal.FollowersOnlyRecipientRepository;
import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.content.internal.QuoteRepository;
import dev.biribiri.elysium.content.internal.StatusIds;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.StatusThreads;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.text.Mentions;
import dev.biribiri.elysium.text.PlainText;
import dev.biribiri.elysium.text.RemoteHtml;
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
import java.time.ZoneOffset;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class StatusService {

    private static final int MAX_ANCESTORS = 40;
    private static final int MAX_DESCENDANTS = 60;
    private static final int MAX_DESCENDANT_DEPTH = 20;

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
            PostingDefaults postingDefaults) {
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
        var text = normalize(draft.text());
        // A content warning that can't be seen would hide nothing, but would still mark the status as sensitive.
        var spoilerText = looksBlank(normalize(draft.spoilerText())) ? "" : normalize(draft.spoilerText());
        var language = draft.language() == null || draft.language().isBlank() ? null : draft.language().strip();
        var mediaIds = checkMediaIds(draft.mediaIds());
        validate(text, spoilerText, language);
        if (mediaIds.isEmpty()) {
            requireText(text);
        }

        var author = actors.findLocalById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("The author isn't a local account"));
        // Before looking up mentions on other servers, so that a reply to nothing doesn't make those requests or use up the
        // author's lookups. The parent is checked again under a lock below, since it can be deleted in between.
        if (draft.inReplyToId() != null && findVisible(draft.inReplyToId(), Optional.of(authorId)).isEmpty()) {
            throw new ReplyParentNotFoundException();
        }
        var quoting = draft.quotedStatusId() == null ? null : quotable(draft.quotedStatusId(), authorId);
        // Quoting a followers-only status, which only its author can, keeps the quote among the same followers.
        var visibility = quoting != null && quoting.status().visibility() == Visibility.PRIVATE
                && (draft.visibility() == Visibility.PUBLIC || draft.visibility() == Visibility.UNLISTED)
                ? Visibility.PRIVATE : draft.visibility();
        var quotePolicy = visibility == Visibility.PRIVATE || visibility == Visibility.DIRECT ? QuotePolicy.NOBODY
                : draft.quotePolicy() != null ? draft.quotePolicy() : postingDefaults.quotePolicy(authorId);
        var mentioned = resolveMentions(author, text);
        var content = quoting == null ? render(text, mentioned)
                : withQuoteFallback(render(text, mentioned), text, linkTo(quoting.status()), quoting.status().uri());

        return transaction.execute(tx -> {
            // Before the status, so that one that can't have them isn't given an ID.
            var claimed = claimMedia(mediaIds, authorId);
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
            var id = nextId(publishedAt);
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
                    0);
            statuses.insert(status);
            quotes.setPolicy(id, quotePolicy.approval());
            media.attach(id, authorId, claimed);
            var mentions = new HashMap<UUID, Boolean>();
            mentionedIds(mentioned, authorId).forEach(mentionedId -> mentions.put(mentionedId, false));
            // A silent mention lets the quoted author see a followers-only quote, and its server be sent it.
            if (quoted != null && !quoted.actorId().equals(authorId)) {
                mentions.putIfAbsent(quoted.actorId(), true);
            }
            statuses.insertMentions(id, mentions);
            statuses.insertTags(id, Hashtags.names(text));
            threads.assign(status, parent.map(Status::uri).orElse(null));
            if (quoted != null) {
                insertLocalQuote(status, quoted, quoting.local());
            }
            events.publishEvent(new StatusStored(id));
            events.publishEvent(new StatusPublished(id, authorId));
            return status;
        });
    }

    /**
     * Replaces the text of one of the author's own statuses, as on Mastodon: its visibility and parent stay as they were.
     * Mentions are looked up again, as for {@link #create}, and an account that the new text no longer mentions keeps the
     * status as a silent mention. Each earlier version is kept. An edit that changes nothing does nothing.
     *
     * @return the status as it is now, or empty if the author has no status with this ID, or it's a boost
     * @throws InvalidStatusException if the new version breaks a validation rule
     */
    public Optional<Status> edit(UUID id, UUID authorId, StatusEdit draft) {
        var text = normalize(draft.text());
        var spoilerText = looksBlank(normalize(draft.spoilerText())) ? "" : normalize(draft.spoilerText());
        var language = draft.language() == null || draft.language().isBlank() ? null : draft.language().strip();
        var newMediaIds = draft.mediaIds() == null ? null : checkMediaIds(draft.mediaIds());
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
        if (statuses.findById(id).filter(found -> isEditableBy(found, authorId)).isEmpty()) {
            return Optional.empty();
        }
        var quote = quotes.find(id).orElse(null);
        if (draft.quotedStatusId() != null && (quote == null || !quotesSame(quote, draft.quotedStatusId(), authorId))) {
            throw new InvalidStatusException("What a status quotes can't be changed");
        }
        var mentioned = resolveMentions(author, text);
        var content = withQuoteFallback(render(text, mentioned), text, quote);

        var edited = transaction.execute(tx -> {
            var found = statuses.findByIdForEdit(id).filter(candidate -> isEditableBy(candidate, authorId));
            if (found.isEmpty()) {
                return new Edited(Optional.<Status>empty(), false);
            }
            var old = found.get();
            var oldPolicy = quotes.policies(List.of(id)).get(id);
            var newPolicy = draft.quotePolicy() == null
                    || old.visibility() == Visibility.PRIVATE || old.visibility() == Visibility.DIRECT
                    ? oldPolicy : draft.quotePolicy().approval();
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
            var unchanged = text.equals(old.text()) && content.equals(old.content())
                    && spoilerText.equals(old.spoilerText()) && Objects.equals(newLanguage, old.language())
                    && sensitive == old.sensitive() && mediaIds.equals(currentIds) && describe.isEmpty();
            if (unchanged) {
                if (policyChanged) {
                    quotes.touchNote(id).ifPresent(at -> events.publishEvent(new StatusNoteChanged(id, authorId, at)));
                }
                return new Edited(found, false);
            }
            // In a later second than the version it replaces, even when that's in the future, since other servers are sent
            // whole seconds and ignore an edit that isn't later than what they have. That includes an Update sent for
            // something other than an edit.
            var since = Stream.concat(Stream.of(old.publishedAt(), old.editedAt()), quotes.findNoteUpdatedAt(id).stream())
                    .filter(Objects::nonNull)
                    .max(Instant::compareTo)
                    .orElseThrow();
            var next = since.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var editedAt = now.isBefore(next) ? next : now;
            if (!statuses.editLocal(id, authorId, text, content, spoilerText, newLanguage, sensitive, editedAt)) {
                return new Edited(Optional.<Status>empty(), false);
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
            statuses.replaceTags(id, Hashtags.names(text));
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

    /**
     * A status that the author is allowed to quote, and what would happen to the quote.
     */
    private record Quoting(Status status, boolean local) {
    }

    /**
     * Quoting a boost quotes the status it boosts, as replying to one does.
     *
     * @throws QuotedStatusNotFoundException if it can't be found, or the author can't see it
     * @throws InvalidStatusException        if its author doesn't allow this author to quote it
     */
    private Quoting quotable(UUID quotedId, UUID authorId) {
        var quoted = findVisible(quotedId, Optional.of(authorId))
                .flatMap(found -> found.isReblog() ? findVisible(found.reblogOfId(), Optional.of(authorId)) : Optional.of(found))
                .orElseThrow(QuotedStatusNotFoundException::new);
        var local = actors.findById(quoted.actorId()).map(actors::isLocal).orElse(false);
        var policy = quotes.policies(List.of(quoted.id())).get(quoted.id());
        var decision = quoteRules.decide(quoted, policy, local, authorId);
        if (decision == QuoteDecision.DENIED) {
            throw new InvalidStatusException("This status can't be quoted");
        }
        return new Quoting(quoted, local);
    }

    /**
     * A quote of a local status is decided here and now. One of a remote status is approved by its author's server,
     * unless its server can't be asked, which a status with no policy says.
     */
    private void insertLocalQuote(Status status, Status quoted, boolean local) {
        var self = quoted.actorId().equals(status.actorId());
        if (local || self) {
            quotes.insert(status.id(), quoted.uri(), quoted.id(), quoted.actorId(), QuoteState.ACCEPTED, false, null,
                    self ? null : Ids.timeOrdered(Instant.now()), null);
            if (!self) {
                events.publishEvent(new QuoteAccepted(status.id(), status.actorId(), quoted.id(), quoted.actorId()));
            }
            return;
        }
        var legacy = quotes.policies(List.of(quoted.id())).get(quoted.id()) == null;
        quotes.insert(status.id(), quoted.uri(), quoted.id(), quoted.actorId(),
                legacy ? QuoteState.ACCEPTED : QuoteState.PENDING, legacy, null, null,
                URI.create(status.uri() + "#quote_request"));
        events.publishEvent(new QuoteRequested(status.id(), status.actorId()));
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

    private static URI linkTo(Status status) {
        return status.url() == null ? status.uri() : status.url();
    }

    /**
     * The fallback for what a status quotes, which is kept through edits, as is the quote.
     */
    private String withQuoteFallback(String content, String text, StatusQuote quote) {
        if (quote == null) {
            return content;
        }
        var link = Optional.ofNullable(quote.quotedStatusId()).flatMap(statuses::findById).map(StatusService::linkTo)
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
    private List<UUID> claimMedia(List<UUID> ids, UUID authorId) {
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

    /**
     * Whether a quote is of this status, or of the status that this one boosts.
     */
    private boolean quotesSame(StatusQuote quote, UUID quotedId, UUID authorId) {
        if (quotedId.equals(quote.quotedStatusId())) {
            return true;
        }
        return statuses.findById(quotedId).filter(Status::isReblog).map(Status::reblogOfId)
                .filter(original -> original.equals(quote.quotedStatusId())).isPresent();
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
        versions.add(StatusVersion.current(status));
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

    private String render(String text, Map<String, Actor> mentioned) {
        if (text.isEmpty()) {
            return "";
        }
        return PlainText.render(text, match -> Optional.ofNullable(mentioned.get(acct(match)))
                .map(actor -> new PlainText.MentionLink(actor.uri(), actor.username() + "@" + actor.domain()))
                .orElse(null), instance.baseUrl());
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
        if (domain == null || domain.equals(instance.domain())) {
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
        var domain = match.domain() == null ? instance.domain() : match.domain();
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
     * Stores a status from another server. Its HTML is sanitized here, so that nothing unsanitized can be stored. Nothing is
     * published for outbound delivery, since it isn't this server's to send; StatusStored updates local inboxes.
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
     * Like {@link #storeRemote}, for an older status fetched from its author's outbox rather than sent here.
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
            var id = backfilled ? backfilledId(remote.publishedAt(), now) : nextId(now);
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
                    0);
            if (!statuses.insert(status)) {
                return Optional.<UUID>empty();
            }
            var mentions = new HashMap<>(remote.mentions());
            mentions.remove(author.id());
            statuses.insertMentions(id, mentions);
            statuses.insertTags(id, remoteTags(remote.tags()));
            attachments.addRemote(id, author.id(), remote.attachments());
            if (remote.quotePolicy() != null) {
                quotes.setPolicy(id, remote.quotePolicy());
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
    private void insertRemoteQuote(UUID statusId, RemoteQuote quote, boolean fetchQuoted) {
        var quoted = statuses.findParentByUriAndLock(quote.quotedUri());
        quotes.insert(statusId, quote.quotedUri(), quoted.map(StatusRepository.Parent::id).orElse(null),
                quoted.map(StatusRepository.Parent::actorId).orElse(null), QuoteState.PENDING, quote.legacy(),
                quote.authorizationUri(), null, null);
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
        // Servers also send an Update when nothing an edit can change has, such as when a quote is approved, and that
        // isn't kept as a version, so that the status doesn't show as edited.
        var stored = statuses.findByUriForAuthor(edit.uri(), edit.authorId());
        if (stored.isPresent() && isSameVersion(stored.get(), html, edit, tags)) {
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
                statuses.replaceTags(editedId, tags);
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

    private boolean isSameVersion(Status status, RemoteText html, RemoteEdit edit, Collection<String> tags) {
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
                    isLanguageCode(language) ? language : null);
        }
    }

    /**
     * Whether this is a language code that statuses can have, such as {@code en} or {@code pt-BR}.
     */
    public static boolean isLanguageCode(String value) {
        return value != null && LANGUAGE.matcher(value).matches();
    }

    /**
     * Clients poll for statuses newer than the last ID they saw, so a status must never become visible after one with a
     * later ID, or they would miss it. The lock this takes is held until commit, which makes IDs and commits happen in the
     * same order.
     */
    UUID nextId(Instant now) {
        statuses.lockIdAssignment();
        return statuses.findNewestId()
                .map(newest -> StatusIds.after(newest, now))
                .orElseGet(() -> StatusIds.fromTimestamp(now));
    }

    /**
     * Takes the same lock as {@link #nextId}, because one dated close to now could otherwise commit ahead of a status that
     * was given an earlier ID, which clients would then miss. A date before 1970 doesn't fit in an ID, and would make one
     * that sorts after every other, so that every later ID would be worked out from it.
     */
    private UUID backfilledId(Instant published, Instant now) {
        statuses.lockIdAssignment();
        var timestamp = published.isAfter(now) ? now : published.isBefore(Instant.EPOCH) ? Instant.EPOCH : published;
        return StatusIds.fromTimestamp(timestamp);
    }

    /** Batch visibility lookup for notifications and conversation summaries. */
    public List<Status> findVisible(Collection<UUID> ids, UUID viewer) {
        if (ids.isEmpty()) return List.of();
        var found = statuses.findByIds(ids);
        var followed = Set.copyOf(follows.followedIds(viewer));
        var mentioned = statuses.mentioning(ids, viewer);
        var refusing = blocks.refusing(viewer, found.stream().map(Status::actorId).collect(Collectors.toSet()));
        return found.stream()
                .filter(status -> isVisible(status, Optional.of(viewer), followed::contains, mentioned::contains,
                        refusing::contains))
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
     * Finds an author's status by its URI, whoever can see it, if the author is active.
     */
    public Optional<Status> findStoredByUri(URI uri, UUID authorId) {
        return statuses.findByUriForAuthor(uri, authorId);
    }

    /** Finds a status if this viewer is allowed to see it. */
    public Optional<Status> findVisible(UUID id, Optional<UUID> viewer) {
        return statuses.findById(id).filter(status -> isVisible(status, viewer));
    }

    /**
     * Whether this viewer is allowed to see a status. Besides what {@link Status#isVisibleTo} allows, a followers-only status
     * can be seen by an account that follows its author, and a followers-only or direct status by the accounts it mentions,
     * as on Mastodon. Nothing by an author who blocks the viewer, or the viewer's server, can be seen, so that they can't
     * favourite, boost or reply to it either.
     */
    public boolean isVisible(Status status, Optional<UUID> viewer) {
        return isVisible(
                status,
                viewer,
                author -> viewer.filter(id -> follows.isFollowing(id, author)).isPresent(),
                statusId -> viewer.filter(id -> !statuses.mentioning(List.of(statusId), id).isEmpty()).isPresent(),
                author -> viewer.filter(id -> !blocks.refusing(id, List.of(author)).isEmpty()).isPresent());
    }

    /**
     * Finds a status if this actor, fetching it from another server, is allowed to see it. Unlike {@link #isVisible}, a
     * followers-only status is only shown to the accounts it mentions, or mentioned before an edit, and to current followers
     * who were sent it, or who were already following when it was posted, for one from before that was recorded. Those are
     * who its {@code Update}s are sent to, and its {@code Delete} reaches all of them, so a fetch tells nobody else that it
     * exists, and nobody can have it without being sent its deletion. Former followers aren't shown it, as they aren't sent
     * its edits.
     *
     * <p>An author who blocks the fetcher, or the fetcher's server, doesn't show it anything.
     *
     * @param fetcher an active actor, or empty for a fetch that nobody is known to be behind
     */
    public Optional<Status> findFetchable(UUID id, Optional<UUID> fetcher) {
        return statuses.findById(id).filter(status -> {
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
     */
    private static boolean isVisible(
            Status status, Optional<UUID> viewer, Predicate<UUID> followsAuthor, Predicate<UUID> mentionsViewer,
            Predicate<UUID> refusesViewer) {
        if (viewer.isPresent() && refusesViewer.test(status.actorId())) {
            return false;
        }
        if (status.isVisibleTo(viewer)) {
            return true;
        }
        if (viewer.isEmpty()) {
            return false;
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
            Predicate<Status> visible = found -> !hiddenAuthors.contains(found.actorId())
                    && isVisible(found, viewer, followed::contains, mentioning::contains, hiddenAuthors::contains);
            return new StatusContext(
                    ancestors.stream().filter(visible).toList(), descendants.stream().filter(visible).toList());
        });
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
                    shownParentAuthor(status), mentioned, boosters));
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
            return deleteRemoteLocked(uri, authorUri);
        }));
        if (removed) {
            attachments.processDeletions();
        }
        return removed;
    }

    /**
     * Deletes a remote status once {@link StatusRepository#lockUri} is held.
     */
    private boolean deleteRemoteLocked(URI uri, URI authorUri) {
        // Locked so that a boost made meanwhile is either found here or fails to find the status.
        var boosted = statuses.findForDeleteByUri(uri);
        var localBoosts = boosted.map(status -> boosts.findLocal(status.id())).orElse(List.of());
        var attached = boosted.map(status -> media.findByStatusAndLock(status.id())).orElse(List.of());
        var deleted = statuses.deleteRemote(uri, authorUri);
        if (deleted) {
            media.removeDetached(attached.stream().map(MediaAttachment::id).toList());
            // Only once it's known to be the author's, since anyone can name any status.
            boosted.ifPresent(status -> localBoosts.forEach(boost ->
                    events.publishEvent(StatusUnreblogged.of(boost, uri, status.actorId()))));
        }
        return deleted;
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
            return deleteRemoteLocked(status.uri(), author.get().uri()) ? Optional.of(status) : Optional.<Status>empty();
        });
        if (removed.isPresent()) {
            attachments.processDeletions();
        }
        return removed;
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
     * Lists public statuses, leaving out replies to other accounts.
     */
    public List<Status> findPublic(StatusOrigin origin, StatusPage page) {
        return findPublic(origin, page, false);
    }

    /**
     * @param onlyMedia whether to only list statuses with attachments
     */
    public List<Status> findPublic(StatusOrigin origin, StatusPage page, boolean onlyMedia) {
        return statuses.findPublic(origin, page, onlyMedia, null);
    }

    /**
     * Like {@link #findPublic(StatusOrigin, StatusPage, boolean)}, leaving out what's hidden from a viewer: statuses by
     * accounts they've blocked, muted, or blocked the server of, or that block them.
     */
    public List<Status> findPublic(StatusOrigin origin, StatusPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.findPublic(origin, page, onlyMedia, viewer.orElse(null));
    }

    /**
     * Lists public statuses with these hashtags, including replies.
     */
    public List<Status> findTagged(TagFilter tags, StatusOrigin origin, StatusPage page) {
        return findTagged(tags, origin, page, false);
    }

    /**
     * @param onlyMedia whether to only list statuses with attachments
     */
    public List<Status> findTagged(TagFilter tags, StatusOrigin origin, StatusPage page, boolean onlyMedia) {
        return statuses.findTagged(tags, origin, page, onlyMedia, null);
    }

    /**
     * Like {@link #findTagged(TagFilter, StatusOrigin, StatusPage, boolean)}, leaving out what's hidden from a viewer, as
     * {@link #findPublic(StatusOrigin, StatusPage, boolean, Optional)} does.
     */
    public List<Status> findTagged(
            TagFilter tags, StatusOrigin origin, StatusPage page, boolean onlyMedia, Optional<UUID> viewer) {
        return statuses.findTagged(tags, origin, page, onlyMedia, viewer.orElse(null));
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
     * Finds hashtags that public statuses here use, by the start of their name, most used first.
     *
     * @param prefix a normalized name
     */
    public List<String> searchTags(String prefix, int limit, int offset) {
        return statuses.searchTags(prefix, limit, offset);
    }

    /**
     * How much each hashtag was used on each of the last {@code days} days, today first, counting public statuses stored
     * here. A day it wasn't used has zeros.
     */
    public Map<String, List<TagDay>> findTagHistory(Collection<String> names, int days) {
        var today = LocalDate.now(ZoneOffset.UTC);
        var first = today.minusDays(days - 1L);
        var used = statuses.tagDays(names, first.atStartOfDay(ZoneOffset.UTC).toInstant());
        var history = new HashMap<String, List<TagDay>>();
        for (var name : names) {
            var byDay = used.getOrDefault(name, List.of()).stream()
                    .collect(Collectors.toMap(TagDay::day, Function.identity()));
            history.put(name, Stream.iterate(today, day -> !day.isBefore(first), day -> day.minusDays(1))
                    .map(day -> byDay.getOrDefault(day, new TagDay(day, 0, 0)))
                    .toList());
        }
        return history;
    }

    /**
     * Another server's tags are only checked here, so that what counts as a tag is decided in one place.
     */
    private static Collection<String> remoteTags(Collection<String> names) {
        return Hashtags.parseAll(names);
    }

    /**
     * Lists the viewer's own statuses and visible statuses from followed accounts, including direct mentions.
     * As on Mastodon, a followed account's reply is only listed when it's to
     * its author, to the viewer, or to someone they follow, so that following one person doesn't show their side of every
     * conversation. A reply to a status that isn't stored here has no known parent author, so it's left out too.
     *
     * @param followedIds the accounts the viewer follows
     */
    public List<Status> findHome(UUID viewerId, Collection<UUID> followedIds, StatusPage page) {
        return statuses.findHome(viewerId, followedIds, page);
    }

    /**
     * Lists the statuses of one account that this viewer is allowed to see, which includes those that mention them, and
     * its boosts unless they're excluded.
     */
    public List<Status> findByAccount(
            UUID actorId, Optional<UUID> viewer, boolean excludeReplies, boolean excludeReblogs, StatusPage page) {
        return findByAccount(actorId, viewer, excludeReplies, excludeReblogs, false, page);
    }

    /**
     * <p>An account that blocks the viewer, or the viewer's server, shows them nothing, and boosts of statuses hidden from
     * the viewer are left out, as on Mastodon.
     *
     * @param onlyMedia whether to only list statuses with attachments, which leaves out boosts
     */
    public List<Status> findByAccount(
            UUID actorId, Optional<UUID> viewer, boolean excludeReplies, boolean excludeReblogs, boolean onlyMedia,
            StatusPage page) {
        if (viewer.isPresent() && !blocks.refusing(viewer.get(), List.of(actorId)).isEmpty()) {
            return List.of();
        }
        var visibilities = viewer.filter(actorId::equals).isPresent()
                ? Set.of(Visibility.values())
                : viewer.filter(id -> follows.isFollowing(id, actorId)).isPresent()
                ? Set.of(Visibility.PUBLIC, Visibility.UNLISTED, Visibility.PRIVATE)
                : Set.of(Visibility.PUBLIC, Visibility.UNLISTED);
        return statuses.findByAccount(
                actorId, visibilities, viewer.orElse(null), excludeReplies, excludeReblogs, onlyMedia, page);
    }

    /**
     * Whether a status with this URI is stored, whoever can see it.
     */
    public boolean isStored(URI uri) {
        return statuses.existsByUri(uri);
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
