package dev.biribiri.elysium.content;

/**
 * What an edit does to a status's poll.
 *
 * @param poll the poll it's to have, or null to remove the one it has
 */
public record PollChange(NewPoll poll) {

    public static PollChange remove() {
        return new PollChange(null);
    }
}
