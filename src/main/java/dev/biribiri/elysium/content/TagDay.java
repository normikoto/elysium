package dev.biribiri.elysium.content;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How much a hashtag or, in trends, a link was used on one day, in UTC, counting public statuses stored here.
 *
 * @param uses     how many statuses had it
 * @param accounts how many accounts posted them
 */
public record TagDay(LocalDate day, long uses, long accounts) {

    /**
     * The {@code days} days up to today, today first, as Mastodon lists history. A day missing from {@code used} has
     * zeros.
     */
    public static List<TagDay> lastDays(LocalDate today, int days, Collection<TagDay> used) {
        var byDay = used.stream().collect(Collectors.toMap(TagDay::day, Function.identity()));
        var first = today.minusDays(days - 1L);
        return Stream.iterate(today, day -> !day.isBefore(first), day -> day.minusDays(1))
                .map(day -> byDay.getOrDefault(day, new TagDay(day, 0, 0)))
                .toList();
    }
}
