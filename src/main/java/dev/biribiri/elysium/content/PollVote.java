package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * One option that someone chose in a poll.
 *
 * @param title what the option said when they chose it
 */
public record PollVote(UUID id, String title) {
}
