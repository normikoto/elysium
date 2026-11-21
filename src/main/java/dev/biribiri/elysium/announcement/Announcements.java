package dev.biribiri.elysium.announcement;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.announcement.internal.AnnouncementRepository;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidReactionException;
import dev.biribiri.elysium.content.ReactionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This server's announcements, which admins write, and which local accounts dismiss and react to.
 */
@Service
public class Announcements {

    /**
     * Mastodon has no limit. This keeps one to what a banner can sensibly show.
     */
    public static final int MAX_TEXT_LENGTH = 5000;

    /**
     * How many different emoji an announcement can be reacted with, by everyone together, as on Mastodon.
     */
    public static final int MAX_REACTIONS = 8;

    private final AnnouncementRepository announcements;
    private final ReactionService reactions;
    private final CustomEmojis emojis;

    Announcements(AnnouncementRepository announcements, ReactionService reactions, CustomEmojis emojis) {
        this.announcements = announcements;
        this.reactions = reactions;
        this.emojis = emojis;
    }

    /**
     * @return every announcement, drafts included, in the order clients show them
     */
    public List<Announcement> list() {
        return announcements.findAll();
    }

    /**
     * @return what clients are shown, in order
     */
    public List<Announcement> listPublished() {
        return announcements.findPublished();
    }

    /**
     * @param startsAt null for none, with {@code endsAt}
     * @param publish  whether to publish it now, rather than keep it as a draft
     * @throws InvalidAnnouncementException if the text is missing or too long, or the times don't go together
     */
    public Announcement create(String text, Instant startsAt, Instant endsAt, boolean allDay, boolean publish) {
        var cleaned = cleanText(text);
        checkTimes(startsAt, endsAt);
        var now = now();
        return announcements.insert(Ids.timeOrdered(now), cleaned, startsAt, endsAt, allDay, publish ? now : null, now);
    }

    /**
     * Keeps whether it's published, and when.
     *
     * @return the announcement, if there is one with this ID
     * @throws InvalidAnnouncementException as for {@link #create}
     */
    public Optional<Announcement> update(UUID id, String text, Instant startsAt, Instant endsAt, boolean allDay) {
        var cleaned = cleanText(text);
        checkTimes(startsAt, endsAt);
        return announcements.update(id, cleaned, startsAt, endsAt, allDay, now());
    }

    /**
     * @return whether there was an announcement with this ID
     */
    public boolean publish(UUID id) {
        return announcements.publish(id, now());
    }

    /**
     * Makes it a draft again. Its dismissals and reactions are kept, for when it's published again.
     *
     * @return whether there was an announcement with this ID
     */
    public boolean unpublish(UUID id) {
        return announcements.unpublish(id);
    }

    /**
     * @return whether there was an announcement with this ID
     */
    public boolean delete(UUID id) {
        return announcements.delete(id);
    }

    /**
     * @return which of these announcements the account has dismissed
     */
    public Set<UUID> dismissedBy(UUID actorId, Collection<UUID> announcementIds) {
        return announcements.findDismissed(actorId, announcementIds);
    }

    /**
     * Doing it again does nothing.
     *
     * @return whether it's a published announcement
     */
    @Transactional
    public boolean dismiss(UUID announcementId, UUID actorId) {
        if (announcements.lockPublished(announcementId).isEmpty()) {
            return false;
        }
        announcements.dismiss(announcementId, actorId);
        return true;
    }

    /**
     * Doing it again does nothing.
     *
     * @param emoji as a client names it, which can be a Unicode emoji or one of this server's custom emoji, as on Mastodon
     * @return whether it's a published announcement
     * @throws InvalidReactionException if the emoji isn't one of those, or the announcement has been reacted with
     *                                  {@value #MAX_REACTIONS} other emoji already
     */
    @Transactional
    public boolean react(UUID announcementId, UUID actorId, String emoji) {
        var choice = choose(emoji);
        if (announcements.lockPublished(announcementId).isEmpty()) {
            return false;
        }
        var emojiId = emojiId(choice);
        if (!announcements.hasReaction(announcementId, choice.name(), emojiId)
                && announcements.countEmoji(announcementId) >= MAX_REACTIONS) {
            throw new InvalidReactionException(
                    "An announcement can't be reacted with more than %d emoji".formatted(MAX_REACTIONS));
        }
        announcements.insertReaction(announcementId, actorId, choice.name(), emojiId, now());
        return true;
    }

    /**
     * Doing it again, or for an emoji the account didn't react with, does nothing.
     *
     * @return whether it's a published announcement
     * @throws InvalidReactionException as for {@link #react}, if the emoji isn't one that can be used
     */
    @Transactional
    public boolean unreact(UUID announcementId, UUID actorId, String emoji) {
        var choice = choose(emoji);
        if (announcements.lockPublished(announcementId).isEmpty()) {
            return false;
        }
        announcements.deleteReaction(announcementId, actorId, choice.name(), emojiId(choice));
        return true;
    }

    /**
     * Each announcement's reactions, by emoji, in the order each emoji was first used, from accounts that can be shown.
     */
    public Map<UUID, List<AnnouncementReaction>> reactions(Collection<UUID> announcementIds, UUID viewer) {
        var rows = announcements.summaries(announcementIds, viewer);
        var emojiById = emojis.findAll(rows.stream().map(AnnouncementRepository.Summary::emojiId)
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(CustomEmoji::id, Function.identity()));
        var found = new HashMap<UUID, List<AnnouncementReaction>>();
        for (var row : rows) {
            var emoji = row.emojiId() == null ? null : emojiById.get(row.emojiId());
            if (row.emojiId() != null && emoji == null) continue;
            found.computeIfAbsent(row.announcementId(), id -> new ArrayList<>())
                    .add(new AnnouncementReaction(row.name(), emoji, row.count(), row.me()));
        }
        return found;
    }

    void removeForActor(UUID actorId) {
        announcements.removeForActor(actorId);
    }

    /**
     * Mastodon only takes this server's own custom emoji for announcements, since they're only shown here.
     */
    private ReactionService.Choice choose(String emoji) {
        var choice = reactions.choose(emoji);
        if (choice.emoji() != null && !choice.emoji().isLocal()) {
            throw new InvalidReactionException("Only this server's custom emoji can be used on announcements");
        }
        return choice;
    }

    private static UUID emojiId(ReactionService.Choice choice) {
        return choice.emoji() == null ? null : choice.emoji().id();
    }

    private static String cleanText(String text) {
        // Browsers send a textarea's line breaks as CRLF, which PlainText wouldn't split paragraphs on, and which the
        // textarea's maxlength counts as one character.
        var cleaned = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (cleaned.isEmpty()) {
            throw new InvalidAnnouncementException("An announcement needs some text.");
        }
        if (cleaned.length() > MAX_TEXT_LENGTH) {
            throw new InvalidAnnouncementException(
                    "Announcements can't be longer than %d characters.".formatted(MAX_TEXT_LENGTH));
        }
        if (!isStorable(cleaned)) {
            throw new InvalidAnnouncementException("Announcements can't contain invalid characters.");
        }
        return cleaned;
    }

    private static void checkTimes(Instant startsAt, Instant endsAt) {
        if ((startsAt == null) != (endsAt == null)) {
            throw new InvalidAnnouncementException("An announcement needs both a start and an end, or neither.");
        }
        if (startsAt != null && endsAt.isBefore(startsAt)) {
            throw new InvalidAnnouncementException("An announcement can't end before it starts.");
        }
    }

    /**
     * What PostgreSQL can store, which would otherwise fail the request with an error the admin can't act on.
     */
    private static boolean isStorable(String text) {
        for (var i = 0; i < text.length(); i++) {
            var character = text.charAt(i);
            var paired = Character.isHighSurrogate(character)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1));
            if (paired) {
                i++;
            } else if (character == '\0' || Character.isSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
