package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A status from another server quotes something, and whether that's approved hasn't been checked.
 *
 * @param fetchQuoted whether the quoted status can be fetched, if it isn't stored. It can't for a status that was only
 *                    stored because something quotes it, so that a chain of quotes can't have this server fetch every
 *                    status in it
 */
public record QuoteVerificationNeeded(UUID statusId, boolean fetchQuoted) {
}
