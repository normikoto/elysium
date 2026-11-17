package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.internal.MediaAttachmentRepository;
import dev.biribiri.elysium.content.internal.ScheduledStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Statuses that local accounts have asked to be posted later, as Mastodon's scheduled statuses. One is checked when it's
 * scheduled, as {@link StatusService#create} would, and again when it's posted, since what it replies to or quotes can
 * have changed in between. Until then nothing about it leaves this service: it isn't federated, streamed or notified.
 */
@Service
public class ScheduledStatusService {

    /**
     * How far ahead a status has to be scheduled, as on Mastodon, which posts it at once otherwise.
     */
    public static final Duration MINIMUM_OFFSET = Duration.ofMinutes(5);

    /**
     * The most that an account can have scheduled, as on Mastodon.
     */
    public static final int TOTAL_LIMIT = 300;

    /**
     * The most that an account can have scheduled for one day, in UTC, as on Mastodon.
     */
    public static final int DAILY_LIMIT = 25;

    /**
     * How many {@link #publishDue} posts in one run. The rest wait for the next.
     */
    private static final int BATCH_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(ScheduledStatusService.class);

    private final ScheduledStatusRepository scheduled;
    private final MediaAttachmentRepository media;
    private final StatusService statuses;
    private final ActorService actors;
    private final AttachmentProperties attachmentProperties;
    private final TransactionTemplate transaction;

    ScheduledStatusService(
            ScheduledStatusRepository scheduled,
            MediaAttachmentRepository media,
            StatusService statuses,
            ActorService actors,
            AttachmentProperties attachmentProperties,
            PlatformTransactionManager transactionManager) {
        this.scheduled = scheduled;
        this.media = media;
        this.statuses = statuses;
        this.actors = actors;
        this.attachmentProperties = attachmentProperties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Schedules a status, holding its media so that it can't be attached to anything else or expire in the meantime.
     *
     * @throws InvalidStatusException       if it's too soon, the author has too many scheduled, or the status breaks a
     *                                      validation rule
     * @throws ReplyParentNotFoundException if the status being replied to doesn't exist or the author can't see it
     * @throws QuotedStatusNotFoundException if the status to quote doesn't exist or the author can't see it
     */
    public ScheduledStatus schedule(UUID authorId, NewStatus draft, Instant scheduledAt) {
        var at = scheduledAt.truncatedTo(ChronoUnit.MICROS);
        checkTime(at);
        statuses.check(authorId, draft);
        var id = Ids.timeOrdered(Instant.now());
        return transaction.execute(tx -> {
            scheduled.lockActor(authorId);
            checkLimits(authorId, at, null);
            var claimed = statuses.claimMedia(draft.mediaIds(), authorId);
            scheduled.insert(id, authorId, at, draft);
            media.hold(id, authorId, claimed);
            return withMedia(scheduled.find(id, authorId).orElseThrow());
        });
    }

    /**
     * @return one of an account's, or empty if it has none with this ID
     */
    public Optional<ScheduledStatus> find(UUID id, UUID authorId) {
        return scheduled.find(id, authorId).map(this::withMedia);
    }

    /**
     * @return an account's, newest first by when they were scheduled
     */
    public List<ScheduledStatus> page(UUID authorId, StatusPage page) {
        var found = scheduled.findPage(authorId, page);
        var held = media.findHeld(found.stream().map(ScheduledStatus::id).toList());
        return found.stream().map(each -> withMedia(each, held.getOrDefault(each.id(), List.of()))).toList();
    }

    /**
     * Changes when one is posted, which is all that can be changed, as on Mastodon.
     *
     * @return it as it is now, or empty if the author has none with this ID
     * @throws InvalidStatusException if it's too soon, or the day it's moved to has too many
     */
    public Optional<ScheduledStatus> reschedule(UUID id, UUID authorId, Instant scheduledAt) {
        var at = scheduledAt.truncatedTo(ChronoUnit.MICROS);
        checkTime(at);
        return transaction.execute(tx -> {
            scheduled.lockActor(authorId);
            if (scheduled.find(id, authorId).isEmpty()) {
                return Optional.<ScheduledStatus>empty();
            }
            checkLimits(authorId, at, id);
            scheduled.reschedule(id, authorId, at);
            return scheduled.find(id, authorId).map(this::withMedia);
        });
    }

    /**
     * Cancels one. Its media becomes unattached uploads again for as long as a deleted status's media is kept, so that it
     * can be posted another way.
     *
     * @return whether the author had one with this ID
     */
    public boolean cancel(UUID id, UUID authorId) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            if (scheduled.findAndLock(id).filter(found -> found.authorId().equals(authorId)).isEmpty()) {
                return false;
            }
            drop(id);
            return true;
        }));
    }

    /**
     * Posts the statuses that are due. One that can no longer be posted, because what it replies to or quotes has gone,
     * its quote is no longer allowed, or its author is suspended or deleted, is dropped, as on Mastodon, and its media is
     * released as {@link #cancel} does. Each is posted at most once, however many times this runs.
     *
     * @return how many were posted or dropped
     */
    public int publishDue() {
        var due = scheduled.findDue(BATCH_SIZE);
        for (var id : due) {
            try {
                publish(id);
            } catch (RuntimeException e) {
                // Left for the next run, rather than holding up the rest.
                log.warn("Couldn't post scheduled status {}", id, e);
            }
        }
        return due.size();
    }

    private void publish(UUID id) {
        var found = scheduled.findById(id);
        if (found.isEmpty()) {
            return;
        }
        var status = withMedia(found.get());
        if (actors.findLocalById(status.authorId()).isEmpty()) {
            dropIfDue(id);
            return;
        }
        try {
            statuses.create(status.authorId(), status.draft(), mediaIds -> claim(id));
        } catch (InvalidStatusException | ReplyParentNotFoundException | QuotedStatusNotFoundException e) {
            log.info("Dropped scheduled status {}, which can't be posted: {}", id, e.getMessage());
            dropIfDue(id);
        }
    }

    /**
     * Takes a scheduled status's media for the status that it's being posted as, and removes it, so that it's only posted
     * once.
     *
     * @return its media, or empty if it's gone or has been moved later since it was found to be due
     */
    private Optional<List<UUID>> claim(UUID id) {
        var locked = scheduled.findAndLock(id).filter(found -> !found.scheduledAt().isAfter(Instant.now()));
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        var held = media.findHeldAndLock(id).stream().map(MediaAttachment::id).toList();
        scheduled.delete(id);
        return Optional.of(held);
    }

    private void dropIfDue(UUID id) {
        transaction.executeWithoutResult(tx -> {
            if (scheduled.findAndLock(id).filter(found -> !found.scheduledAt().isAfter(Instant.now())).isPresent()) {
                drop(id);
            }
        });
    }

    /**
     * Has to be called with the scheduled status locked.
     */
    private void drop(UUID id) {
        media.release(id, Instant.now().plus(attachmentProperties.redraftRetention()));
        scheduled.delete(id);
    }

    /**
     * Removes every one of an account's, for when it's deleted. Their media is left for whatever removes the account's
     * uploads.
     */
    public void removeAllBy(UUID authorId) {
        scheduled.removeAllBy(authorId);
    }

    private static void checkTime(Instant at) {
        if (at.isBefore(Instant.now().plus(MINIMUM_OFFSET))) {
            throw new InvalidStatusException(
                    "A status has to be scheduled at least %d minutes ahead".formatted(MINIMUM_OFFSET.toMinutes()));
        }
    }

    /**
     * Has to be called with {@link ScheduledStatusRepository#lockActor} held.
     *
     * @param moving one that's being moved to that time, which already counts toward the total, or null for a new one
     */
    private void checkLimits(UUID authorId, Instant at, UUID moving) {
        if (moving == null && scheduled.count(authorId) >= TOTAL_LIMIT) {
            throw new InvalidStatusException(
                    "An account can't have more than %d scheduled statuses".formatted(TOTAL_LIMIT));
        }
        if (scheduled.countOn(authorId, at.atZone(ZoneOffset.UTC).toLocalDate(), moving) >= DAILY_LIMIT) {
            throw new InvalidStatusException(
                    "An account can't have more than %d statuses scheduled for one day".formatted(DAILY_LIMIT));
        }
    }

    private ScheduledStatus withMedia(ScheduledStatus status) {
        return withMedia(status, media.findHeld(List.of(status.id())).getOrDefault(status.id(), List.of()));
    }

    private static ScheduledStatus withMedia(ScheduledStatus status, Collection<MediaAttachment> held) {
        var draft = status.draft();
        var withIds = new NewStatus(draft.text(), draft.spoilerText(), draft.language(), draft.visibility(),
                draft.sensitive(), draft.inReplyToId(), held.stream().map(MediaAttachment::id).toList(),
                draft.quotedStatusId(), draft.quotePolicy(), draft.poll(), draft.localOnly(), draft.contentType());
        return new ScheduledStatus(status.id(), status.authorId(), status.scheduledAt(), withIds, List.copyOf(held));
    }
}
