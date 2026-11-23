package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActiveAccounts;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The twelve weeks of counts that Mastodon's {@code GET /api/v1/instance/activity} gives, this week first.
 */
@Component
class WeeklyCounts {

    static final int WEEKS = 12;

    /**
     * Anyone can ask for these without a token, and counting reads every local status of the last twelve weeks, so they
     * aren't counted again for each request. Mastodon keeps its own for a day.
     */
    private static final Duration KEPT_FOR = Duration.ofHours(1);

    private final StatusService statuses;
    private final ActiveAccounts activeAccounts;
    private final ActorService actors;

    private volatile Counted counted;

    WeeklyCounts(StatusService statuses, ActiveAccounts activeAccounts, ActorService actors) {
        this.statuses = statuses;
        this.activeAccounts = activeAccounts;
        this.actors = actors;
    }

    List<WeeklyCountsResponse> lastTwelveWeeks() {
        var now = Instant.now();
        var thisWeek = LocalDate.ofInstant(now, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var current = counted;
        // A new week has to show up as soon as it starts, even if the counts from the last one are still recent.
        if (current == null || !current.thisWeek().equals(thisWeek) || current.at().plus(KEPT_FOR).isBefore(now)) {
            current = new Counted(now, thisWeek, count(thisWeek));
            counted = current;
        }
        return current.weeks();
    }

    private List<WeeklyCountsResponse> count(LocalDate thisWeek) {
        var since = thisWeek.minusWeeks(WEEKS - 1);
        var statusCounts = statuses.countLocalByWeek(since);
        var logins = activeAccounts.countByWeek(since);
        var registrations = actors.countLocalCreatedByWeek(since);
        var weeks = new ArrayList<WeeklyCountsResponse>(WEEKS);
        for (var week = thisWeek; !week.isBefore(since); week = week.minusWeeks(1)) {
            weeks.add(new WeeklyCountsResponse(
                    Long.toString(week.atStartOfDay().toEpochSecond(ZoneOffset.UTC)),
                    countIn(statusCounts, week),
                    countIn(logins, week),
                    countIn(registrations, week)));
        }
        return List.copyOf(weeks);
    }

    private static String countIn(Map<LocalDate, Long> counts, LocalDate week) {
        return Long.toString(counts.getOrDefault(week, 0L));
    }

    private record Counted(Instant at, LocalDate thisWeek, List<WeeklyCountsResponse> weeks) {
    }
}
