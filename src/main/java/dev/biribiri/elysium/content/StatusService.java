package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.internal.StatusIds;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.content.internal.StatusThreads;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.text.Mentions;
import dev.biribiri.elysium.text.PlainText;
import dev.biribiri.elysium.text.RemoteHtml;
import dev.biribiri.elysium.text.Urls;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final RemoteActorResolver resolver;

    StatusService(
            StatusRepository statuses,
            StatusThreads threads,
            ActorService actors,
            InstanceProperties instance,
            StatusProperties properties,
            PlatformTransactionManager transactionManager,
            ApplicationEventPublisher events,
            FollowService follows,
            RemoteActorResolver resolver) {
        this.resolver = resolver;
        this.statuses = statuses;
        this.threads = threads;
        this.actors = actors;
        this.follows = follows;
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
     */
    public Status create(UUID authorId, NewStatus draft) {
        var text = normalize(draft.text());
        // A content warning that can't be seen would hide nothing, but would still mark the status as sensitive.
        var spoilerText = looksBlank(normalize(draft.spoilerText())) ? "" : normalize(draft.spoilerText());
        var language = draft.language() == null || draft.language().isBlank() ? null : draft.language().strip();
        validate(text, spoilerText, language);

        var author = actors.findLocalById(authorId)
                .orElseThrow(() -> new IllegalArgumentException("The author isn't a local account"));
        // Before looking up mentions on other servers, so that a reply to nothing doesn't make those requests or use up the
        // author's lookups. The parent is checked again under a lock below, since it can be deleted in between.
        if (draft.inReplyToId() != null && findVisible(draft.inReplyToId(), Optional.of(authorId)).isEmpty()) {
            throw new ReplyParentNotFoundException();
        }
        var mentioned = resolveMentions(author, text);
        var content = PlainText.render(text, match -> Optional.ofNullable(mentioned.get(acct(match)))
                .map(actor -> new PlainText.MentionLink(actor.uri(), actor.username() + "@" + actor.domain()))
                .orElse(null));

        return transaction.execute(tx -> {
            var parent = draft.inReplyToId() == null
                    ? Optional.<Status>empty()
                    : Optional.of(statuses.findByIdAndLock(draft.inReplyToId())
                    .filter(candidate -> isVisible(candidate, Optional.of(authorId)))
                    .orElseThrow(ReplyParentNotFoundException::new));

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
                    draft.visibility(),
                    draft.sensitive() || !spoilerText.isEmpty(),
                    parent.isPresent(),
                    parent.map(Status::id).orElse(null),
                    parent.map(Status::actorId).orElse(null),
                    null,
                    null,
                    publishedAt,
                    null,
                    0);
            statuses.insert(status);
            statuses.insertMentions(id, mentioned.values().stream()
                    .map(Actor::id)
                    .filter(mentionedId -> !mentionedId.equals(authorId))
                    .distinct()
                    .collect(Collectors.toMap(Function.identity(), mentionedId -> false)));
            threads.assign(status, parent.map(Status::uri).orElse(null));
            events.publishEvent(new StatusStored(id));
            events.publishEvent(new StatusPublished(id, authorId));
            return status;
        });
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
            var id = nextId(Instant.now());
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
                    0);
            if (!statuses.insert(status)) {
                return Optional.<UUID>empty();
            }
            var mentions = new HashMap<>(remote.mentions());
            mentions.remove(author.id());
            statuses.insertMentions(id, mentions);
            // Jobs have no order, so replies can be stored before their parent.
            statuses.linkWaitingReplies(remote.uri(), new StatusRepository.Parent(id, author.id()));
            threads.assign(status, remote.inReplyToUri());
            events.publishEvent(new StatusStored(id));
            return Optional.of(id);
        });
    }

    /**
     * Replaces a remote status with a version its author's server edited. Only what an author can change in an edit on
     * Mastodon is replaced, so its visibility, its parent and when it was published stay as they were. So do its mentions,
     * which decide who can see a followers-only or direct status, so that an edit can't show it to anyone it wasn't sent
     * to. Nothing is published for this edit, since it isn't this server's to send.
     *
     * @return what happened to the edit
     */
    public RemoteEdit.Outcome updateRemote(RemoteEdit edit) {
        var html = RemoteText.of(edit.content(), edit.spoilerText(), edit.language());
        // One statement, so it needs no lock: a Delete or Create that runs alongside it ends the same in either order. A
        // caller that stores the status after NOT_STORED has to cope with one stored in between.
        var edited = statuses.updateRemote(
                edit.uri(),
                edit.authorId(),
                html.text(),
                html.content(),
                html.spoilerText(),
                html.language(),
                edit.sensitive() || !html.spoilerText().isEmpty(),
                edit.editedAt().truncatedTo(ChronoUnit.MICROS));
        if (edited) {
            return RemoteEdit.Outcome.EDITED;
        }
        return statuses.existsByUri(edit.uri()) ? RemoteEdit.Outcome.UNCHANGED : RemoteEdit.Outcome.NOT_STORED;
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
    private UUID nextId(Instant now) {
        statuses.lockIdAssignment();
        return statuses.findNewestId()
                .map(newest -> StatusIds.after(newest, now))
                .orElseGet(() -> StatusIds.fromTimestamp(now));
    }

    /** Batch visibility lookup for notifications and conversation summaries. */
    public List<Status> findVisible(Collection<UUID> ids, UUID viewer) {
        if (ids.isEmpty()) return List.of();
        var found = statuses.findByIds(ids);
        var followed = Set.copyOf(follows.followedIds(viewer));
        var mentioned = statuses.mentioning(ids, viewer);
        return found.stream().filter(status -> isVisible(status, Optional.of(viewer), followed::contains, mentioned::contains)).toList();
    }

    /** Finds a status if this viewer is allowed to see it. */
    public Optional<Status> findVisible(UUID id, Optional<UUID> viewer) {
        return statuses.findById(id).filter(status -> isVisible(status, viewer));
    }

    /**
     * Whether this viewer is allowed to see a status. Besides what {@link Status#isVisibleTo} allows, a followers-only status
     * can be seen by an account that follows its author, and a followers-only or direct status by the accounts it mentions,
     * as on Mastodon.
     */
    public boolean isVisible(Status status, Optional<UUID> viewer) {
        return isVisible(
                status,
                viewer,
                author -> viewer.filter(id -> follows.isFollowing(id, author)).isPresent(),
                statusId -> viewer.filter(id -> !statuses.mentioning(List.of(statusId), id).isEmpty()).isPresent());
    }

    /**
     * Finds a status if this actor, fetching it from another server, is allowed to see it. Unlike {@link #isVisible}, a
     * followers-only status is only shown to followers who were already following when it was posted, and to the accounts it
     * shows as mentioned. Those are who its {@code Create} and {@code Delete} are sent to, so a fetch tells nobody else that
     * it exists, and nobody can have it without being sent its deletion.
     *
     * @param fetcher an active actor, or empty for a fetch that nobody is known to be behind
     */
    public Optional<Status> findFetchable(UUID id, Optional<UUID> fetcher) {
        return statuses.findById(id).filter(status -> {
            if (status.isVisibleTo(fetcher)) {
                return true;
            }
            if (fetcher.isEmpty()) {
                return false;
            }
            var mentioned = statuses.findMentions(List.of(id)).getOrDefault(id, List.of()).contains(fetcher.get());
            return mentioned || (status.visibility() == Visibility.PRIVATE
                    && follows.wasFollowingAsOf(fetcher.get(), status.actorId(), status.publishedAt()));
        });
    }

    /**
     * @param followsAuthor  whether the viewer follows an author, for a caller that has looked that up already
     * @param mentionsViewer whether a status mentions the viewer, for a caller that has looked that up already
     */
    private static boolean isVisible(
            Status status, Optional<UUID> viewer, Predicate<UUID> followsAuthor, Predicate<UUID> mentionsViewer) {
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
     * same limits as Mastodon has for a viewer who isn't signed in. Only what's stored here is listed.
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
            Predicate<Status> visible = found -> isVisible(found, viewer, followed::contains, mentioning::contains);
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
     * Deletes one of the author's own statuses. A tombstone is only kept for a status that anyone could see, because
     * others get the same answer for a followers-only or direct status as for one that never existed, and a tombstone would
     * reveal that it had.
     *
     * @return the deleted status, or empty if the author has no status with this ID
     */
    public Optional<Status> delete(UUID id, UUID authorId) {
        return transaction.execute(tx -> {
            // Before the status goes, and its mentions with it.
            var mentioned = statuses.findAllMentioned(id);
            var deleted = statuses.delete(id, authorId);
            deleted.ifPresent(status -> {
                if (status.isVisibleTo(Optional.empty())) {
                    statuses.insertTombstone(status, Instant.now().truncatedTo(ChronoUnit.MICROS));
                }
                events.publishEvent(new StatusDeleted(
                        status.id(), status.uri(), authorId, status.visibility(), status.publishedAt(),
                        shownParentAuthor(status), mentioned));
            });
            return deleted;
        });
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
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            statuses.lockUri(uri);
            statuses.markRemoteDeleted(uri, authorUri);
            return statuses.deleteRemote(uri, authorUri);
        }));
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
    }

    /**
     * Removes the statuses of every remote actor on a domain, for when it's blocked. Each batch commits by itself.
     *
     * @param domain a domain as {@code actor.domain} has it
     */
    public void removeForDomain(String domain) {
        statuses.removeForDomain(domain);
        threads.removeForDomain(domain);
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
        return statuses.findPublic(origin, page);
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
     * Lists the statuses of one account that this viewer is allowed to see, which includes those that mention them.
     */
    public List<Status> findByAccount(UUID actorId, Optional<UUID> viewer, boolean excludeReplies, StatusPage page) {
        var visibilities = viewer.filter(actorId::equals).isPresent()
                ? Set.of(Visibility.values())
                : viewer.filter(id -> follows.isFollowing(id, actorId)).isPresent()
                ? Set.of(Visibility.PUBLIC, Visibility.UNLISTED, Visibility.PRIVATE)
                : Set.of(Visibility.PUBLIC, Visibility.UNLISTED);
        return statuses.findByAccount(actorId, visibilities, viewer.orElse(null), excludeReplies, page);
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
        if (looksBlank(text)) {
            throw new InvalidStatusException("Text can't be blank");
        }
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
        for (var i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                throw new InvalidStatusException("Text contains invalid characters");
            }
        }
    }
}
