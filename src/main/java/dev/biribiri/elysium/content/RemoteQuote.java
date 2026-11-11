package dev.biribiri.elysium.content;

import java.net.URI;

/**
 * The status that a status from another server quotes, as its server says.
 *
 * @param legacy           whether it's only said with properties from before FEP-044f, such as {@code _misskey_quote}, from
 *                         a server that can't ask for consent
 * @param authorizationUri its {@code quoteAuthorization}, which hasn't been checked, or null
 */
public record RemoteQuote(URI quotedUri, boolean legacy, URI authorizationUri) {
}
