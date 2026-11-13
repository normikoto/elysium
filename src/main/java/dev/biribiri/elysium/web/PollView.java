package dev.biribiri.elysium.web;

import dev.biribiri.elysium.content.Poll;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A poll, for people to read. Counts are left out while a poll that hides them is open, as in the API.
 *
 * @param closes when voting closes or closed, for people to read, or null if it doesn't
 * @param votes  how many votes it has, or null while they're hidden
 */
record PollView(List<Option> options, boolean ended, String closes, Integer votes) {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    /**
     * @param percent its share of the votes, rounded, or null while counts are hidden
     */
    record Option(String title, Integer percent) {
    }

    static PollView of(Poll poll, Instant now) {
        var ended = poll.isExpired(now);
        var hidden = poll.hideTotals() && !ended;
        var total = poll.votesCount();
        return new PollView(
                poll.options().stream()
                        .map(option -> new Option(option.title(), hidden ? null
                                : total == 0 ? 0 : Math.round(100f * option.votesCount() / total)))
                        .toList(),
                ended,
                poll.endsAt() == null ? null : DISPLAY.format(poll.endsAt()),
                hidden ? null : total);
    }
}
