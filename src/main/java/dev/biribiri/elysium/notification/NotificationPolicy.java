package dev.biribiri.elysium.notification;

import java.util.Arrays;
import java.util.Optional;

/**
 * What an account does with notifications from each kind of sender, with Mastodon's names. A sender the account accepted
 * a request from, and an admin's mention, always get through.
 *
 * @param forLimitedAccounts kept so that clients can set it and read it back, but nothing matches it, since accounts and
 *                           servers can't be limited yet
 */
public record NotificationPolicy(Treatment forNotFollowing, Treatment forNotFollowers, Treatment forNewAccounts,
                                 Treatment forPrivateMentions, Treatment forLimitedAccounts, Treatment forBots) {

    public static final NotificationPolicy DEFAULT = new NotificationPolicy(Treatment.ACCEPT, Treatment.ACCEPT,
            Treatment.ACCEPT, Treatment.FILTER, Treatment.FILTER, Treatment.ACCEPT);

    /**
     * In order of strictness, so that when several apply, the strictest one decides.
     */
    public enum Treatment {
        ACCEPT("accept"),
        FILTER("filter"),
        DROP("drop");

        private final String value;

        Treatment(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Optional<Treatment> fromValue(String value) {
            return Arrays.stream(values()).filter(treatment -> treatment.value.equals(value)).findFirst();
        }

        public Treatment stricter(Treatment other) {
            return compareTo(other) >= 0 ? this : other;
        }
    }

    /**
     * Each one that's null stays as it is.
     */
    public record Change(Treatment forNotFollowing, Treatment forNotFollowers, Treatment forNewAccounts,
                         Treatment forPrivateMentions, Treatment forLimitedAccounts, Treatment forBots) {
    }

    NotificationPolicy with(Change change) {
        return new NotificationPolicy(
                change.forNotFollowing() == null ? forNotFollowing : change.forNotFollowing(),
                change.forNotFollowers() == null ? forNotFollowers : change.forNotFollowers(),
                change.forNewAccounts() == null ? forNewAccounts : change.forNewAccounts(),
                change.forPrivateMentions() == null ? forPrivateMentions : change.forPrivateMentions(),
                change.forLimitedAccounts() == null ? forLimitedAccounts : change.forLimitedAccounts(),
                change.forBots() == null ? forBots : change.forBots());
    }
}
