package dev.biribiri.elysium.content;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Who can quote a local status, as Mastodon offers it. Whether their quotes are approved automatically or by its author is
 * chosen apart from this, with {@link QuoteReview}.
 */
public enum QuotePolicy {
    PUBLIC,
    /** Its author's followers, and its author. */
    FOLLOWERS,
    /** Only its author. */
    NOBODY;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<QuotePolicy> fromValue(String value) {
        return Arrays.stream(values()).filter(policy -> policy.value().equals(value)).findFirst();
    }

    /**
     * Only a status anyone can quote has accounts that don't follow its author among those who can, so for one that only
     * followers can quote, reviewing non-followers' quotes is the same as reviewing none.
     */
    QuoteApproval approval(QuoteReview review) {
        var followers = Set.of(QuoteApproval.Audience.FOLLOWERS);
        return switch (this) {
            case PUBLIC -> switch (review) {
                case NONE -> new QuoteApproval(Set.of(QuoteApproval.Audience.PUBLIC), Set.of());
                case NON_FOLLOWERS -> new QuoteApproval(followers, Set.of(QuoteApproval.Audience.PUBLIC));
                case ALL -> new QuoteApproval(Set.of(), Set.of(QuoteApproval.Audience.PUBLIC));
            };
            case FOLLOWERS -> review == QuoteReview.ALL
                    ? new QuoteApproval(Set.of(), followers) : new QuoteApproval(followers, Set.of());
            case NOBODY -> new QuoteApproval(Set.of(), Set.of());
        };
    }
}
