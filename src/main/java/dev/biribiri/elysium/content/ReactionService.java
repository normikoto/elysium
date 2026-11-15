package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.internal.LikeRepository;
import dev.biribiri.elysium.content.internal.ReactionRepository;
import dev.biribiri.elysium.content.internal.StatusRepository;
import dev.biribiri.elysium.text.Shortcodes;
import dev.biribiri.elysium.text.UnicodeEmoji;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Emoji reactions to stored statuses, as Pleroma and Misskey have them. They're separate from likes, so a reaction
 * doesn't count as a favourite, and an actor can react with several emoji.
 *
 * <p>Inbound reactions are kept by their activity's ID, with a receipt for each {@code Undo}, since jobs have no order and
 * an {@code Undo} can run first. Servers that send reactions always undo them by ID.
 */
@Service
public class ReactionService {

    /**
     * How many different emoji one account can react to a status with, so that one can't fill a status with them.
     */
    public static final int MAX_PER_ACCOUNT = 20;

    /**
     * How many of each emoji's accounts a listing shows.
     */
    public static final int MAX_LISTED_ACCOUNTS = 40;

    private final ReactionRepository reactions;
    private final LikeRepository likes;
    private final StatusRepository repository;
    private final ActorService actors;
    private final CustomEmojis emojis;
    private final ApplicationEventPublisher events;
    private final StatusHolds holds;

    ReactionService(ReactionRepository reactions, LikeRepository likes, StatusRepository repository, ActorService actors,
                    CustomEmojis emojis, ApplicationEventPublisher events, StatusHolds holds) {
        this.reactions = reactions;
        this.likes = likes;
        this.repository = repository;
        this.actors = actors;
        this.emojis = emojis;
        this.events = events;
        this.holds = holds;
    }

    /**
     * An emoji as a reaction names it.
     *
     * @param name  the Unicode emoji, or the custom emoji's shortcode
     * @param emoji the custom emoji, or null for a Unicode one
     */
    public record Choice(String name, CustomEmoji emoji) {

        UUID emojiId() {
            return emoji == null ? null : emoji.id();
        }
    }

    /**
     * Reads an emoji as a client names it, as Pleroma reads them: one Unicode emoji, a shortcode of this server's, with or
     * without colons, or {@code shortcode@domain} for another server's that's stored here. A Unicode emoji is in its
     * fully qualified form, so that the same one counts once however it's sent.
     *
     * @throws InvalidReactionException if it's none of those
     */
    public Choice choose(String value) {
        var given = value == null ? "" : value.strip();
        if (UnicodeEmoji.isOne(given)) {
            return new Choice(UnicodeEmoji.normalize(given), null);
        }
        var bare = given.length() > 2 && given.startsWith(":") && given.endsWith(":")
                ? given.substring(1, given.length() - 1) : given;
        var at = bare.indexOf('@');
        var shortcode = Shortcodes.parse(at < 0 ? bare : bare.substring(0, at));
        if (shortcode.isPresent()) {
            var emoji = at < 0
                    ? emojis.findLocal(List.of(shortcode.get())).values().stream().findFirst()
                    : emojis.findRemote(shortcode.get(), bare.substring(at + 1));
            if (emoji.isPresent()) {
                return new Choice(shortcode.get(), emoji.get());
            }
        }
        throw new InvalidReactionException("Not an emoji that can be reacted with");
    }

    /**
     * Doing it again does nothing.
     *
     * @return the status, or empty if it isn't one the account can see
     * @throws InvalidReactionException if the account has reacted with {@value #MAX_PER_ACCOUNT} other emoji already
     */
    @Transactional
    public Optional<Status> react(UUID statusId, UUID actorId, Choice choice) {
        return holdVisible(statusId, actorId).flatMap(status -> actors.findLocalById(actorId).map(actor -> {
            reactions.lockActorOnStatus(actor.uri().toString(), statusId);
            reactions.lockReaction(actor.uri().toString(), statusId, choice.name(), choice.emojiId());
            if (reactions.findOne(actorId, statusId, choice.name(), choice.emojiId()).isEmpty()) {
                if (reactions.countFor(actorId, statusId) >= MAX_PER_ACCOUNT) {
                    throw new InvalidReactionException(
                            "You can't react with more than %d emoji".formatted(MAX_PER_ACCOUNT));
                }
                var activity = actor.uri() + "#reactions/" + UUID.randomUUID();
                var reaction = reactions.insert(actorId, statusId, choice.name(), choice.emojiId(), activity, now());
                events.publishEvent(new StatusReacted(reaction.id(), actorId, statusId, status.actorId()));
            }
            return status;
        }));
    }

    /**
     * Doing it again, or for an emoji the account didn't react with, does nothing.
     *
     * @return the status, or empty if it isn't one the account can see
     */
    @Transactional
    public Optional<Status> unreact(UUID statusId, UUID actorId, Choice choice) {
        return holdVisible(statusId, actorId).flatMap(status -> actors.findLocalById(actorId).map(actor -> {
            reactions.lockReaction(actor.uri().toString(), statusId, choice.name(), choice.emojiId());
            reactions.findOne(actorId, statusId, choice.name(), choice.emojiId()).ifPresent(reaction -> {
                reactions.delete(reaction.id());
                events.publishEvent(new StatusUnreacted(actorId, statusId, status.actorId(), status.uri(),
                        reaction.activityUri(), reaction.name(), reaction.emojiId()));
            });
            return status;
        }));
    }

    private Optional<Status> holdVisible(UUID statusId, UUID actorId) {
        return holds.holdVisible(statusId, actorId);
    }

    public boolean wasUndone(String activityUri, String actorUri) {
        return reactions.wasUndone(activityUri, actorUri);
    }

    /**
     * Another server's reaction to a local status. A repeat of a reaction that's kept already keeps the newer activity's
     * ID, as likes do, and one past the limit is dropped.
     *
     * @param name       the Unicode emoji, or the custom emoji's shortcode
     * @param remote     the custom emoji, or null for a Unicode one
     * @param emojiDomain the server the actor says a custom emoji is from, or null for its own. One from its own is stored
     *                   under its domain, as a status's are. One from another server is only that server's emoji if it's
     *                   stored here already, since the actor's server can't speak for it, and the reaction is dropped
     *                   otherwise
     */
    @Transactional
    public void receive(UUID actorId, UUID statusId, String name, RemoteEmoji remote, String emojiDomain,
                        String activityUri, Instant firstArrival) {
        holdVisible(statusId, actorId).ifPresent(status -> {
            var actor = actors.findById(actorId).orElseThrow();
            if (actors.isLocal(actor) || actors.findLocalById(status.actorId()).isEmpty()) return;
            var own = emojiDomain == null || emojiDomain.equalsIgnoreCase(actor.domain());
            UUID emojiId = null;
            if (remote != null && own) {
                emojiId = emojis.storeRemote(actor.domain(), List.of(remote)).get(remote.shortcode());
            } else if (remote != null) {
                var stored = emojis.findRemote(remote.shortcode(), emojiDomain.toLowerCase(Locale.ROOT));
                if (stored.isEmpty()) return;
                emojiId = stored.get().id();
            }
            var reactionName = remote == null ? UnicodeEmoji.normalize(name) : name;
            var actorUri = actor.uri().toString();
            reactions.lockActorOnStatus(actorUri, statusId);
            reactions.lockReaction(actorUri, statusId, reactionName, emojiId);
            reactions.lockActivity(activityUri);
            if (reactions.wasUndone(activityUri, actorUri)) return;
            var existing = reactions.findOne(actorId, statusId, reactionName, emojiId);
            if (existing.isPresent()) {
                if (!existing.get().activityAt().isAfter(firstArrival)) {
                    reactions.replaceActivity(existing.get().id(), activityUri, firstArrival);
                }
                return;
            }
            if (reactions.findActivity(actorUri, activityUri).isPresent()
                    || reactions.countFor(actorId, statusId) >= MAX_PER_ACCOUNT) return;
            var reaction = reactions.insert(actorId, statusId, reactionName, emojiId, activityUri, firstArrival);
            events.publishEvent(new StatusReacted(reaction.id(), actorId, statusId, status.actorId()));
        });
    }

    /**
     * Withdraws another server's reaction by its activity's ID, and remembers that it was, in case the reaction arrives
     * later.
     */
    @Transactional
    public void undo(String actorUri, String activityUri, Instant undoAt) {
        withdraw(actorUri, activityUri, true, undoAt);
    }

    /**
     * Like {@link #undo}, without remembering it, for an {@code Undo} of a {@code Like}, whose receipt for likes also
     * stops a reaction that arrives later.
     */
    @Transactional
    public void undoLike(String actorUri, String activityUri) {
        withdraw(actorUri, activityUri, false, null);
    }

    /**
     * Takes the locks {@link #receive} does, in the same order, so that a repeat of the reaction under a newer ID that's
     * being stored meanwhile is either replaced before this looks, or stored after this has removed the old one.
     */
    private void withdraw(String actorUri, String activityUri, boolean remember, Instant undoAt) {
        var found = reactions.findActivity(actorUri, activityUri);
        found.ifPresent(reaction -> {
            likes.holdActors(List.of(reaction.actorId()));
            likes.holdStatus(reaction.statusId());
            reactions.lockActorOnStatus(actorUri, reaction.statusId());
            reactions.lockReaction(actorUri, reaction.statusId(), reaction.name(), reaction.emojiId());
        });
        if (found.isEmpty() && !remember) return;
        reactions.lockActivity(activityUri);
        if (remember) {
            reactions.markUndone(activityUri, actorUri, undoAt);
        }
        reactions.deleteActivity(actorUri, activityUri);
    }

    /**
     * Holds the reaction until an outbound listener has queued its delivery, so that a late listener can't send it after
     * its {@code Undo}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StatusReaction> findAndHold(UUID reactionId) {
        var preview = reactions.find(reactionId);
        if (preview.isEmpty()) return Optional.empty();
        var reaction = preview.get();
        // A suspended local sender keeps the reaction, and its delivery waits for unsuspension, as for likes.
        var actor = actors.findAnyLocalById(reaction.actorId()).filter(found -> !found.isDeleted());
        var status = repository.findById(reaction.statusId());
        if (actor.isEmpty() || status.isEmpty()) return Optional.empty();
        likes.holdActors(List.of(reaction.actorId(), status.get().actorId()));
        if (!likes.eligibleActor(status.get().actorId()) || !likes.holdStatus(reaction.statusId())) return Optional.empty();
        reactions.lockReaction(actor.get().uri().toString(), reaction.statusId(), reaction.name(), reaction.emojiId());
        return reactions.find(reactionId);
    }

    /**
     * @return by ID, those that are still there
     */
    public Map<UUID, StatusReaction> findAll(Collection<UUID> reactionIds) {
        return reactions.findByIds(reactionIds).stream()
                .collect(Collectors.toMap(StatusReaction::id, Function.identity()));
    }

    /**
     * Each status's reactions, by emoji, in the order each emoji was first used, from accounts that can be shown.
     */
    public Map<UUID, List<ReactionSummary>> summaries(Collection<UUID> statusIds, Optional<UUID> viewer) {
        return summaries(statusIds, viewer, 0);
    }

    /**
     * Like {@link #summaries}, with the most recent of each emoji's accounts, up to {@value #MAX_LISTED_ACCOUNTS}.
     */
    public List<ReactionSummary> withAccounts(UUID statusId, Optional<UUID> viewer) {
        return summaries(List.of(statusId), viewer, MAX_LISTED_ACCOUNTS).getOrDefault(statusId, List.of());
    }

    private Map<UUID, List<ReactionSummary>> summaries(Collection<UUID> statusIds, Optional<UUID> viewer, int accounts) {
        var rows = reactions.summaries(statusIds, viewer, accounts);
        var emojiById = emojis.findAll(rows.stream().map(ReactionRepository.Summary::emojiId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(CustomEmoji::id, Function.identity()));
        var found = new HashMap<UUID, List<ReactionSummary>>();
        for (var row : rows) {
            var emoji = row.emojiId() == null ? null : emojiById.get(row.emojiId());
            if (row.emojiId() != null && emoji == null) continue;
            found.computeIfAbsent(row.statusId(), id -> new ArrayList<>()).add(new ReactionSummary(
                    nameOf(row.name(), emoji), emoji, row.count(), row.me(), row.actorIds()));
        }
        return found;
    }

    /**
     * How clients name a reaction, as Pleroma does, so that one can be sent back to react the same way.
     */
    public static String nameOf(String name, CustomEmoji emoji) {
        return emoji == null || emoji.isLocal() ? name : name + "@" + emoji.domain();
    }

    public void removeForActor(UUID actorId) {
        reactions.removeForActor(actorId);
    }

    public void removeForDomain(String domain) {
        holds.forActorsOn(domain, reactions::removeForActors);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
