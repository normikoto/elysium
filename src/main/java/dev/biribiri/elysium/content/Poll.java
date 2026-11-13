package dev.biribiri.elysium.content;

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
        List<Integer> ownVotes
) {

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
        if (closedAt != null && (expiresAt == null || closedAt.isBefore(expiresAt))) {
            return closedAt;
        }
        return expiresAt;
    }

    public boolean isExpired(Instant now) {
        var end = endsAt();
        return end != null && !end.isAfter(now);
    }

    public int votesCount() {
        return options.stream().mapToInt(Option::votesCount).sum();
    }

    public List<String> titles() {
        return options.stream().map(Option::title).toList();
    }
}
