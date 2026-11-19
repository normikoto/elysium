package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Fetches documents from other servers, as the instance actor when no user is behind a request, or as a local actor that
 * the other server shows more to. This doesn't depend on any signature format.
 */
@Component
class SignedFetcher {

    private static final byte[] NO_BODY = new byte[0];

    private final FederationHttpClient http;
    private final SignerFallback signers;
    private final ActorSigningKeys keys;

    SignedFetcher(FederationHttpClient http, List<RequestSigner> signers, ActorSigningKeys keys) {
        this.http = http;
        this.signers = new SignerFallback(signers);
        this.keys = keys;
    }

    /**
     * Blocks while fetching, so this mustn't be called inside a database transaction.
     *
     * @return the first response that wasn't a refusal, or else the last refusal
     * @throws DocumentFetchException if this URL can't be signed. URLs often come from other servers, so this can happen.
     * @throws IOException       as for {@link FederationHttpClient#get}
     */
    Response get(URI url, String accept) throws IOException, DocumentFetchException {
        return get(url, accept, keys.forInstance());
    }

    /**
     * Like {@link #get(URI, String)}, signed as a local actor, or as the instance actor if the actor has no key.
     */
    Response getAs(URI url, String accept, Actor actor) throws IOException, DocumentFetchException {
        return get(url, accept, keys.forActor(actor).orElseGet(keys::forInstance));
    }

    private Response get(URI url, String accept, SigningKey key) throws IOException, DocumentFetchException {
        return signers.firstNotRefused(signer -> http.get(url, accept, sign(signer, url, key)));
    }

    private static HttpHeaders sign(RequestSigner signer, URI url, SigningKey key) throws DocumentFetchException {
        try {
            return signer.sign("GET", url, NO_BODY, key);
        } catch (IllegalArgumentException e) {
            throw DocumentFetchException.permanent("Can't sign a request to " + url, e);
        }
    }
}
