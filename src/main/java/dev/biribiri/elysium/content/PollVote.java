package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * One option that someone chose in a poll.
 *
 * @param title what the option said when they chose it
 * @param uri   the ID its server gave it, or null if it's a vote from here
 */
public record PollVote(UUID id, UUID statusId, UUID voterId, int position, String title, URI uri, Instant createdAt) {
}
