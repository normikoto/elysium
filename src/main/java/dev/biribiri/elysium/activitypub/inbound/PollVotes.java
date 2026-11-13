package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.StatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;

/**
 * Counts votes in local polls, which Mastodon sends as a {@code Create} of a {@code Note} whose {@code name} is the option
 * chosen, in reply to the poll, with no content. {@link ActorNotes} has checked that the note is its actor's, as for any
 * other {@code Note}. The voter has to be able to see the poll, as for a fetch, and the rest is up to
 * {@link PollService#receiveVote}. A vote that's refused is dropped, rather than stored as a reply.
 */
@Component
class PollVotes {

    private static final Logger log = LoggerFactory.getLogger(PollVotes.class);

    private final LikeService likes;
    private final StatusService statuses;
    private final PollService polls;
    private final RemoteActors remoteActors;

    PollVotes(LikeService likes, StatusService statuses, PollService polls, RemoteActors remoteActors) {
        this.likes = likes;
        this.statuses = statuses;
        this.polls = polls;
        this.remoteActors = remoteActors;
    }

    /**
     * Blocks while fetching the voter, if it isn't stored, so this mustn't be called inside a database transaction.
     *
     * @return whether the note was a vote in a local poll, counted or not
     */
    boolean receive(InboundActivity activity, RemoteNote note) {
        if (!isVoteShaped(note)) {
            return false;
        }
        var statusId = likes.localStatusId(note.inReplyTo());
        if (statusId.isEmpty() || !polls.hasLocalPoll(statusId.get())) {
            return false;
        }
        var voter = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        if (statuses.findFetchable(statusId.get(), Optional.of(voter.id())).isEmpty()) {
            log.debug("Dropping vote {}, whose voter can't see the poll", activity.id());
            return true;
        }
        var outcome = polls.receiveVote(statusId.get(), voter.id(), note.id(), note.name());
        if (outcome != PollService.VoteOutcome.COUNTED) {
            log.debug("Not counting vote {}: {}", activity.id(), outcome);
        }
        return true;
    }

    /**
     * A reply with content is a reply, even with a name.
     */
    private static boolean isVoteShaped(RemoteNote note) {
        return note.inReplyTo() != null && note.name() != null && !note.name().isEmpty() && note.poll() == null
                && (note.content() == null || note.content().isBlank());
    }
}
