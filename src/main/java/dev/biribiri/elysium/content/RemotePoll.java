package dev.biribiri.elysium.content;

import java.time.Instant;
import java.util.List;

/**
 * A poll on a status from another server, as its server described it.
 *
 * @param options     the answers, in order
 * @param counts      the votes for each answer, in the same order
 * @param expiresAt   when its server says voting closes, or null if it doesn't say
 * @param closedAt    when its server says it closed, or null if it hasn't
 * @param votersCount how many accounts its server says have voted, or null if it doesn't say
 */
public record RemotePoll(
        List<String> options, List<Integer> counts, boolean multiple, Instant expiresAt, Instant closedAt,
        Integer votersCount) {

    /**
     * More than any server lets its users give, so that a poll is only cut short if its server is being unreasonable.
     */
    public static final int MAX_OPTIONS = 32;

    public static final int MAX_OPTION_CHARACTERS = 1000;

    public RemotePoll {
        if (options.size() != counts.size()) {
            throw new IllegalArgumentException("Each option needs a count");
        }
        options = List.copyOf(options);
        counts = List.copyOf(counts);
    }
}
