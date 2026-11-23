package dev.biribiri.elysium.content;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Which of a local account's own statuses and boosts are deleted once they're old enough, with the options of Mastodon's
 * automated post deletion. The {@code keep} options only protect statuses: a boost is deleted once it's old enough.
 *
 * @param minFavourites a status with at least this many favourites is kept, or null to keep none for this
 * @param minBoosts     a status boosted at least this many times is kept, or null to keep none for this
 */
public record PostCleanupPolicy(
        boolean enabled,
        MinimumAge minimumAge,
        boolean keepPinned,
        boolean keepDirect,
        boolean keepPolls,
        boolean keepMedia,
        boolean keepSelfFavourited,
        boolean keepSelfBookmarked,
        Integer minFavourites,
        Integer minBoosts) {

    /**
     * Mastodon's defaults, which an account has until it chooses.
     */
    public static final PostCleanupPolicy DEFAULT = new PostCleanupPolicy(
            false, MinimumAge.TWO_WEEKS, true, true, false, false, true, true, null, null);

    public PostCleanupPolicy {
        if (minimumAge == null) {
            throw new InvalidPostCleanupPolicyException("choose how old posts have to be");
        }
        if (minFavourites != null && minFavourites < 1 || minBoosts != null && minBoosts < 1) {
            throw new InvalidPostCleanupPolicyException("the numbers of favourites and boosts have to be at least 1");
        }
    }

    /**
     * The ages Mastodon offers, which are all a policy can have.
     */
    public enum MinimumAge {
        ONE_WEEK(7, "1 week"),
        TWO_WEEKS(14, "2 weeks"),
        ONE_MONTH(30, "1 month"),
        TWO_MONTHS(60, "2 months"),
        THREE_MONTHS(90, "3 months"),
        SIX_MONTHS(180, "6 months"),
        ONE_YEAR(365, "1 year"),
        TWO_YEARS(730, "2 years");

        private final int days;
        private final String label;

        MinimumAge(int days, String label) {
            this.days = days;
            this.label = label;
        }

        public int days() {
            return days;
        }

        public String label() {
            return label;
        }

        public Duration duration() {
            return Duration.ofDays(days);
        }

        public static Optional<MinimumAge> ofDays(int days) {
            return Arrays.stream(values()).filter(age -> age.days == days).findFirst();
        }
    }
}
