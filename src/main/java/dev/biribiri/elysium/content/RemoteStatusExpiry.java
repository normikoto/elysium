package dev.biribiri.elysium.content;

import dev.biribiri.elysium.content.internal.RemoteStatusExpiryRepository;
import dev.biribiri.elysium.content.internal.RemoteStatusExpiryRepository.Cursor;
import dev.biribiri.elysium.content.internal.StatusIds;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Removes other servers' statuses once they've been stored for {@link StatusProperties#remoteRetention}, when an admin
 * turns that on, as Mastodon's content cache retention does. A status that someone here favourited, boosted, bookmarked,
 * reacted to, voted in, replied in the thread of, quoted, filtered, or reported, or that mentions them, is notified about,
 * or is in their conversations, is kept, and so is what else {@link StatusReferences} says to keep.
 *
 * <p>A run only removes so many, so that turning it on works through a backlog over many runs rather than holding up
 * everything else that stores statuses. It goes on from where the last run stopped, since statuses grow old in the order
 * of their IDs. It only goes back over the statuses it kept now and then, which is how one that's been unbookmarked since,
 * or that was backfilled with an old ID, is found.
 *
 * <p>Each query only checks a window of so many statuses, local ones included, and a run only checks so many windows,
 * so that a stretch where nearly everything is kept, as on a pass back over them, takes many short queries rather than
 * one that reads the whole table.
 */
@Service
public class RemoteStatusExpiry {

    static final int WINDOW = 500;
    static final int WINDOWS_PER_RUN = 100;
    static final int MAX_REMOVED_PER_RUN = 10_000;
    static final Duration PASS_INTERVAL = Duration.ofDays(7);

    private final RemoteStatusExpiryRepository repository;
    private final StatusProperties properties;
    private final List<StatusReferences> references;

    RemoteStatusExpiry(RemoteStatusExpiryRepository repository, StatusProperties properties,
                       List<StatusReferences> references) {
        this.repository = repository;
        this.properties = properties;
        this.references = references;
    }

    /**
     * @return how many statuses were removed
     */
    public int runDue() {
        return run(Instant.now());
    }

    /**
     * Like {@link #runDue}, as if it were {@code now}, so that tests needn't wait for statuses to grow old.
     */
    public int run(Instant now) {
        return run(now, WINDOW, WINDOWS_PER_RUN);
    }

    int run(Instant now, int window, int windowsPerRun) {
        if (properties.remoteRetention() == null) return 0;
        var before = now.minus(properties.remoteRetention());
        var conditions = references.stream()
                .map(reference -> "(" + reference.refersTo("s.id") + ")")
                .collect(Collectors.joining("\nOR "));
        var cursor = repository.findCursor();
        UUID after = cursor.scannedTo();
        var passStartedAt = after == null ? now : cursor.passStartedAt();
        var removed = 0;
        for (var i = 0; i < windowsPerRun && removed < MAX_REMOVED_PER_RUN; i++) {
            var end = repository.findWindowEnd(before, after, window);
            var expired = repository.findExpired(conditions, before, after, end.orElse(null));
            if (!expired.isEmpty()) {
                removed += repository.remove(expired, conditions, before);
            }
            if (end.isEmpty()) {
                // Everything older than the cutoff has been looked at, so the next run only needs what's grown old
                // since, unless it's time to go back over what was kept.
                after = passStartedAt != null && passStartedAt.isAfter(now.minus(PASS_INTERVAL))
                        ? StatusIds.firstAt(before)
                        : null;
                break;
            }
            after = end.get();
        }
        repository.saveCursor(new Cursor(after, passStartedAt));
        return removed;
    }
}
