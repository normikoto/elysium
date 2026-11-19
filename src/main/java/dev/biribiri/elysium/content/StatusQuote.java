package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * The status that a status quotes.
 *
 * @param statusId        the quoting status
 * @param quotedStatusId  the quoted status, or null if it isn't stored, or has been deleted
 * @param quotedAccountId the quoted status's author, or null if that isn't known
 * @param linked          whether the quoted status was ever stored, so that one without it has been deleted
 * @param legacy          whether its server can't ask for consent, or the quoted status's server can't give it, so that
 *                        it's accepted without a {@code QuoteAuthorization}
 * @param approvalUri     the {@code QuoteAuthorization} from another server, or null
 * @param stampId         the {@code QuoteAuthorization} this server gave, or null
 * @param requestUri      the {@code QuoteRequest} that asked for approval, or null
 * @param requestedAt     when this server last asked for approval of a local quote, or when a quote of a local status was
 *                        last put to its author to review, or null
 * @param decidedAt       when it was last accepted, rejected, or revoked, or null while it's pending, and for one decided
 *                        before this was kept
 */
public record StatusQuote(
        UUID statusId,
        URI quotedUri,
        UUID quotedStatusId,
        UUID quotedAccountId,
        boolean linked,
        QuoteState state,
        boolean legacy,
        URI approvalUri,
        UUID stampId,
        URI requestUri,
        Instant requestedAt,
        Instant decidedAt
) {

    /**
     * The ID of a quote's first {@code QuoteRequest}. Each one is derived from the status, so that deleting the status can
     * cancel them all, by {@link #requestUriPrefix}, without reading a row that's already gone.
     */
    public static URI requestUriFor(URI statusUri) {
        return URI.create(requestUriPrefix(statusUri));
    }

    /**
     * The ID of a quote's {@code QuoteRequest} when its author asks again. It's new, since a server that answered the first
     * one gives a repeat the same answer.
     */
    public static URI requestUriFor(URI statusUri, Instant askedAt) {
        return URI.create(requestUriPrefix(statusUri) + "/" + askedAt.toEpochMilli());
    }

    public static String requestUriPrefix(URI statusUri) {
        return statusUri + "#quote_request";
    }

    /**
     * Whether a quote of a local status is waiting for its author to approve or reject it.
     */
    public boolean isUnderReview() {
        return state == QuoteState.PENDING && requestedAt != null;
    }

    /**
     * Whether the quoted status was accepted and has been deleted since, which Mastodon's API shows as {@code deleted}.
     */
    public boolean isOfDeleted() {
        return state == QuoteState.ACCEPTED && linked && quotedStatusId == null;
    }
}
