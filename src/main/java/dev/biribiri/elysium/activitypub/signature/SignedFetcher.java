package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Fetches documents from other servers as the instance actor, for when no user is behind a request.
 *
 * <p>This doesn't depend on any signature format. There's no way to tell which formats a server accepts before asking,
 * so this tries each {@link RequestSigner} in order, and moves on to the next one if the server refuses the request.
 */
@Component
class SignedFetcher {

    private static final byte[] NO_BODY = new byte[0];

    private final FederationHttpClient http;
    private final List<RequestSigner> signers;
    private final ActorSigningKeys keys;

    SignedFetcher(FederationHttpClient http, List<RequestSigner> signers, ActorSigningKeys keys) {
        if (signers.isEmpty()) {
            throw new IllegalArgumentException("At least one signer is needed");
        }
        this.http = http;
        this.signers = List.copyOf(signers);
        this.keys = keys;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @return the first response that wasn't a refusal, or else the last refusal
     * @throws KeyFetchException if this URL can't be signed. URLs often come from other servers, so this can happen.
     * @throws IOException       as for {@link FederationHttpClient#get}
     */
    Response get(URI url, String accept) throws IOException, KeyFetchException {
        var key = keys.forInstance();
        Response response = null;
        for (var signer : signers) {
            // Signed for each try, so that the time in a signature is never out of date.
            response = http.get(url, accept, sign(signer, url, key));
            if (!isRefusal(response)) {
                return response;
            }
        }
        return response;
    }

    private static HttpHeaders sign(RequestSigner signer, URI url, SigningKey key) throws KeyFetchException {
        try {
            return signer.sign("GET", url, NO_BODY, key);
        } catch (IllegalArgumentException e) {
            throw KeyFetchException.permanent("Can't sign a request to " + url, e);
        }
    }

    static boolean isRefusal(Response response) {
        return response.status() == 401 || response.status() == 403;
    }
}
