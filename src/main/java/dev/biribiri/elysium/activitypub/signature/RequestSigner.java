package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.http.HttpHeaders;

import java.net.URI;

/**
 * Signs outgoing requests in one signature format. A request should be signed when it's sent rather than when it's
 * queued, because servers reject signatures that are too old.
 *
 * <p>There can be several of these, so a request that's signed for no particular server should go through
 * {@link SignedFetcher} rather than choosing a format. Signers that are preferred should have a lower
 * {@link org.springframework.core.annotation.Order}.
 */
public interface RequestSigner {

    /**
     * @param body the request's body, which is empty if it doesn't have one
     * @return the headers to add to the request
     * @throws IllegalArgumentException if the target doesn't have a host that can be signed. Targets often come from
     *                                  other servers, so this isn't always a mistake in the caller.
     */
    HttpHeaders sign(String method, URI target, byte[] body, SigningKey key);
}
