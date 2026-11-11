package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.jobs.QueuedJob;

import java.time.Instant;
import java.util.UUID;

/**
 * Checks a remote status's quote, through {@link QuoteVerifications}.
 *
 * @param statusId    the quoting status
 * @param quotedUri   what it quoted when this was queued, so that this does nothing once it quotes something else
 * @param approvalUri the {@code QuoteAuthorization} it had then, or null
 * @param fetchQuoted whether the quoted status can be fetched
 * @param noteTurn    the turn {@link TargetFetchTurns} gave it to fetch the quoted status, or null
 * @param stampTurn   the turn it was given to fetch the {@code QuoteAuthorization}, or null
 */
record QuoteVerificationJob(
        UUID statusId, String quotedUri, String approvalUri, boolean fetchQuoted, Instant queuedAt, Instant noteTurn,
        Instant stampTurn) implements QueuedJob {

    QuoteVerificationJob withNoteTurn(Instant turn) {
        return new QuoteVerificationJob(statusId, quotedUri, approvalUri, fetchQuoted, queuedAt, turn, stampTurn);
    }

    QuoteVerificationJob withStampTurn(Instant turn) {
        return new QuoteVerificationJob(statusId, quotedUri, approvalUri, fetchQuoted, queuedAt, noteTurn, turn);
    }
}
