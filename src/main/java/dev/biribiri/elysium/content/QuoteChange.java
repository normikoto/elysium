package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * What an edit does to what a status quotes.
 *
 * @param quotedStatusId the status it's to quote, or null to remove the quote it has
 */
public record QuoteChange(UUID quotedStatusId) {
}
