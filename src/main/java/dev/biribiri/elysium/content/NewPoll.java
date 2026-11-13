package dev.biribiri.elysium.content;

import java.util.List;

/**
 * A poll that an author submits with a status.
 *
 * @param options   the answers, in order
 * @param expiresIn how long it's open for, in seconds
 */
public record NewPoll(List<String> options, long expiresIn, boolean multiple, boolean hideTotals) {

    public NewPoll {
        options = List.copyOf(options);
    }
}
