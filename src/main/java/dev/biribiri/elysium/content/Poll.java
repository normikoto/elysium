package dev.biribiri.elysium.content;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A status's poll, as one viewer sees it.
 *
 * @param local       whether the status is this server's. A remote poll's counts are what its server last said, plus
 *                    votes from here since
 * @param expiresAt   when voting closes, or null if it doesn't. Only a poll from another server can have none
 * @param closedAt    when its server said it closed, which can be before it was due to, or null if it hasn't said
 * @param votersCount how many accounts have voted, which is less than the number of votes when they can choose more than
 *                    one
 * @param fetchedAt   when a remote poll's counts were last fetched from its server, or null if they haven't been, or it's
 *                    local
 * @param ownVotes    the positions of the options the viewer chose, or null if there's no viewer
 */
public record Poll(
        UUID statusId,
        boolean local,
        boolean multiple,
        boolean hideTotals,
        Instant expiresAt,
        Instant closedAt,
        List<Option> options,
        int votersCount,
        Instant fetchedAt,
        List<Integer> ownVotes
) {

    /**
     * How old a remote poll's counts can be before a viewer asking for them has them fetched again.
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /**
     * The most options a local poll can have, as on Mastodon.
     */
    public static final int MAX_OPTIONS = 4;

    /**
     * The most characters each option of a local poll can have, as on Mastodon.
     */
    public static final int MAX_OPTION_CHARACTERS = 50;

    /**
     * The shortest time a local poll can be open for, in seconds, as on Mastodon.
     */
    public static final int MIN_EXPIRATION = 300;

    /**
     * The longest time a local poll can be open for, in seconds, which is a month, as on Mastodon.
     */
    public static final int MAX_EXPIRATION = 2_629_746;

    public Poll {
        options = List.copyOf(options);
        ownVotes = ownVotes == null ? null : List.copyOf(ownVotes);
    }

    public record Option(String title, int votesCount) {
    }

    /**
     * When voting closed or will, or null if it never does.
     */
    public Instant endsAt() {
        return endsAt(expiresAt, closedAt);
    }

    public boolean isExpired(Instant now) {
        return isPast(endsAt(), now);
    }

    /**
     * Whether its counts are hidden, which they are while a poll that hides its totals is open, as on Mastodon.
     */
    public boolean countsHidden(Instant now) {
        return hideTotals && !isExpired(now);
    }

    /**
     * Whether a remote poll's counts are old enough to fetch again. One that had ended when it was last fetched won't
     * change.
     */
    public boolean isStale(Instant now) {
        return !local && (fetchedAt == null
                || (fetchedAt.plus(STALE_AFTER).isBefore(now) && !isPast(endsAt(), fetchedAt)));
    }

    /**
     * When voting closes: the earlier of when it's due to, and when its server said it closed.
     */
    public static Instant endsAt(Instant expiresAt, Instant closedAt) {
        if (closedAt != null && (expiresAt == null || closedAt.isBefore(expiresAt))) {
            return closedAt;
        }
        return expiresAt;
    }

    public static boolean isPast(Instant at, Instant now) {
        return at != null && !at.isAfter(now);
    }

    public int votesCount() {
        return options.stream().mapToInt(Option::votesCount).sum();
    }

    public List<String> titles() {
        return options.stream().map(Option::title).toList();
    }
}
