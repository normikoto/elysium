package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * Someone voted in a poll, here or on another server.
 *
 * @param statusId the poll's status
 * @param voteIds  one for each option they chose
 */
public record PollVoted(UUID statusId, UUID voterId, List<UUID> voteIds) {

    public PollVoted {
        voteIds = List.copyOf(voteIds);
    }
}
