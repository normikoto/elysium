package dev.biribiri.elysium.content;

/**
 * The two statuses a {@code QuoteAuthorization} that this server gave is about.
 *
 * @param quoting the status that quotes
 * @param quoted  the status it's approved to quote
 */
public record QuoteStamp(Status quoting, Status quoted) {
}
