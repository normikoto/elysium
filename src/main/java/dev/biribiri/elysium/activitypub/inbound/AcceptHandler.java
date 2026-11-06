package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.graph.FollowService;
import org.springframework.stereotype.Component;

/**
 * Makes a local account's follow of a remote actor active, once that actor accepts it. Only answers to a {@code Follow} are
 * acted on so far, and other types can be added here by looking at what the {@code object} is.
 */
@Component
class AcceptHandler implements InboundActivityHandler {

    private final FollowAnswers answers;
    private final FollowService follows;

    AcceptHandler(FollowAnswers answers, FollowService follows) {
        this.answers = answers;
        this.follows = follows;
    }

    @Override
    public String type() {
        return "Accept";
    }

    @Override
    public void handle(InboundActivity activity) {
        answers.read(activity).ifPresent(answer -> {
            if (answer.followId() != null) {
                follows.acceptRequest(answer.followed().id(), answer.followId());
            } else {
                follows.acceptRequestFrom(answer.follower().id(), answer.followed().id());
            }
        });
    }

    /**
     * Accepting is the same each time, so a repeat does no harm, and a server that uses the same ID for each answer between
     * two actors could be answering a new request.
     */
    @Override
    public boolean processesRepeats() {
        return true;
    }
}
