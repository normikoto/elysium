package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.http.HttpHeaders;

import java.net.URI;

/**
 * Signs outgoing requests in one signature format. A request should be signed when it's sent rather than when it's
 * queued, because servers reject signatures that are too old.
 */
public interface RequestSigner {

    /**
     * @param body the request's body, which is empty if it doesn't have one
     * @return the headers to add to the request
     */
    HttpHeaders sign(String method, URI target, byte[] body, SigningKey key);
}
