package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

/**
 * Removes a local account's follow of a remote actor when that actor rejects it, whether it was waiting for an answer or
 * already accepted, since servers also send a {@code Reject} to remove a follower. Only answers to a {@code Follow} are
 * acted on, and answers to a {@code QuoteRequest}, through {@link QuoteAnswers}.
 */
@Component
class RejectHandler implements InboundActivityHandler {

    private final FollowAnswers answers;
    private final FollowService follows;
    private final QuoteAnswers quoteAnswers;

    RejectHandler(FollowAnswers answers, FollowService follows, QuoteAnswers quoteAnswers) {
        this.answers = answers;
        this.follows = follows;
        this.quoteAnswers = quoteAnswers;
    }

    @Override
    public String type() {
        return "Reject";
    }

    @Override
    public void handle(InboundActivity activity) {
        if (quoteAnswers.answer(activity, false)) {
            return;
        }
        answers.read(activity).ifPresent(answer -> {
            if (answer.followId() != null) {
                follows.rejectRequest(answer.followed().id(), answer.followId());
            } else {
                follows.rejectRequestFrom(answer.follower(), answer.followed().id());
            }
        });
    }

    /**
     * A server that uses the same ID for each answer between two actors could be rejecting a new request.
     */
    @Override
    public boolean processesRepeats() {
        return true;
    }
}
