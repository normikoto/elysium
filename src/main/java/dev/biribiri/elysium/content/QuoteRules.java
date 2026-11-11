package dev.biribiri.elysium.content;

import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Decides whether an account can quote a status, the same way for posting a quote, for answering another server's
 * {@code QuoteRequest}, and for telling clients.
 */
@Component
class QuoteRules {

    private final FollowService follows;
    private final BlockService blocks;

    QuoteRules(FollowService follows, BlockService blocks) {
        this.follows = follows;
        this.blocks = blocks;
    }

    /**
     * @param policy the quoted status's, or null if it has none
     * @param local  whether the quoted status is this server's. One from before quotes can only be quoted by its author.
     *               A remote one with no policy is from a server that can't be asked, so it's quoted as such servers
     *               quote each other
     */
    QuoteDecision decide(Status quoted, QuoteApproval policy, boolean local, UUID quoter) {
        return decide(quoted, policy, local, Optional.of(quoter), follows::isFollowing,
                (author, viewer) -> !blocks.refusing(viewer, List.of(author)).isEmpty());
    }

    /**
     * @param follows whether the first account follows the second
     * @param refuses whether the first account, an author, blocks the second or its server
     */
    static QuoteDecision decide(
            Status quoted, QuoteApproval policy, boolean local, Optional<UUID> quoter, BiPredicate<UUID, UUID> follows,
            BiPredicate<UUID, UUID> refuses) {
        if (quoted.isReblog() || quoted.visibility() == Visibility.DIRECT) {
            return QuoteDecision.DENIED;
        }
        var author = quoted.actorId();
        if (quoter.filter(author::equals).isPresent()) {
            return QuoteDecision.AUTOMATIC;
        }
        if (quoted.visibility() == Visibility.PRIVATE) {
            return QuoteDecision.DENIED;
        }
        if (quoter.isEmpty()) {
            return QuoteDecision.UNKNOWN;
        }
        if (refuses.test(author, quoter.get())) {
            return QuoteDecision.DENIED;
        }
        if (policy == null) {
            return local ? QuoteDecision.DENIED : QuoteDecision.AUTOMATIC;
        }
        if (includes(policy.automatic(), author, quoter.get(), follows)) {
            return QuoteDecision.AUTOMATIC;
        }
        if (includes(policy.manual(), author, quoter.get(), follows)) {
            return QuoteDecision.MANUAL;
        }
        if (policy.automatic().contains(QuoteApproval.Audience.UNSUPPORTED)
                || policy.manual().contains(QuoteApproval.Audience.UNSUPPORTED)) {
            return QuoteDecision.UNKNOWN;
        }
        return QuoteDecision.DENIED;
    }

    private static boolean includes(
            java.util.Set<QuoteApproval.Audience> audiences, UUID author, UUID quoter, BiPredicate<UUID, UUID> follows) {
        return audiences.contains(QuoteApproval.Audience.PUBLIC)
                || (audiences.contains(QuoteApproval.Audience.FOLLOWERS) && follows.test(quoter, author))
                || (audiences.contains(QuoteApproval.Audience.FOLLOWING) && follows.test(author, quoter));
    }
}
