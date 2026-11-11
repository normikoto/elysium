package dev.biribiri.elysium.content;

import java.net.URI;
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
        URI requestUri
) {

    /**
     * The ID of a quote's {@code QuoteRequest}. It's derived from the status, so that deleting the status can cancel the
     * request without reading a row that's already gone. A status's quote can't change, so it never needs another.
     */
    public static URI requestUriFor(URI statusUri) {
        return URI.create(statusUri + "#quote_request");
    }

    /**
     * Whether the quoted status was accepted and has been deleted since, which Mastodon's API shows as {@code deleted}.
     */
    public boolean isOfDeleted() {
        return state == QuoteState.ACCEPTED && linked && quotedStatusId == null;
    }
}
