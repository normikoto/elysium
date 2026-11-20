package dev.biribiri.elysium.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static dev.biribiri.elysium.content.QuoteApproval.Audience.FOLLOWERS;
import static dev.biribiri.elysium.content.QuoteApproval.Audience.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;

class QuotePolicyTests {

    @Test
    void othersAreReviewedAsThePublicBesideWhoeverIsApprovedAutomatically() {
        assertThat(QuotePolicy.PUBLIC.approval(OtherQuotes.REVIEW)).isEqualTo(new QuoteApproval(Set.of(PUBLIC), Set.of()));
        assertThat(QuotePolicy.FOLLOWERS.approval(OtherQuotes.REJECT))
                .isEqualTo(new QuoteApproval(Set.of(FOLLOWERS), Set.of()));
        assertThat(QuotePolicy.FOLLOWERS.approval(OtherQuotes.REVIEW))
                .isEqualTo(new QuoteApproval(Set.of(FOLLOWERS), Set.of(PUBLIC)));
        assertThat(QuotePolicy.NOBODY.approval(OtherQuotes.REJECT)).isEqualTo(new QuoteApproval(Set.of(), Set.of()));
        assertThat(QuotePolicy.NOBODY.approval(OtherQuotes.REVIEW)).isEqualTo(new QuoteApproval(Set.of(), Set.of(PUBLIC)));
    }

    @ParameterizedTest
    @EnumSource(QuotePolicy.class)
    void anApprovalReadsBackAsThePolicyAndOthersItWasMadeFrom(QuotePolicy policy) {
        for (var others : OtherQuotes.values()) {
            var approval = policy.approval(others);
            assertThat(approval.policy()).isEqualTo(policy);
            if (policy != QuotePolicy.PUBLIC) {
                assertThat(approval.others()).isEqualTo(others);
            }
        }
    }

    @Test
    void aChangeKeepsWhatHappensToOtherQuotesUnlessThePolicyWasPublic() {
        var reviewed = QuotePolicy.NOBODY.approval(OtherQuotes.REVIEW);
        assertThat(StatusService.changedPolicy(reviewed, QuotePolicy.FOLLOWERS, null, () -> OtherQuotes.REJECT))
                .isEqualTo(QuotePolicy.FOLLOWERS.approval(OtherQuotes.REVIEW));

        var open = QuotePolicy.PUBLIC.approval(OtherQuotes.REJECT);
        assertThat(StatusService.changedPolicy(open, QuotePolicy.FOLLOWERS, null, () -> OtherQuotes.REVIEW))
                .isEqualTo(QuotePolicy.FOLLOWERS.approval(OtherQuotes.REVIEW));

        assertThat(StatusService.changedPolicy(reviewed, null, OtherQuotes.REJECT, () -> OtherQuotes.REVIEW))
                .isEqualTo(QuotePolicy.NOBODY.approval(OtherQuotes.REJECT));
        assertThat(StatusService.changedPolicy(null, null, null, () -> OtherQuotes.REVIEW)).isNull();
        assertThat(StatusService.changedPolicy(null, null, OtherQuotes.REVIEW, () -> OtherQuotes.REJECT))
                .isEqualTo(QuotePolicy.NOBODY.approval(OtherQuotes.REVIEW));
    }
}
