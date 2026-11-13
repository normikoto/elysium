package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A poll's voting has closed, which is published once for each poll, shortly after it does.
 *
 * @param statusId the poll's status
 * @param local    whether the status is this server's
 */
public record PollEnded(UUID statusId, UUID authorId, boolean local) {
}
