package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Sends activities to other servers.
 */
@Component
public class SignedSender {

    private static final String ACTIVITY_JSON = "application/activity+json";

    private final FederationHttpClient http;
    private final SignerFallback signers;

    SignedSender(FederationHttpClient http, List<RequestSigner> signers) {
        this.http = http;
        this.signers = new SignerFallback(signers);
    }

    /**
     * Blocks while sending, so this mustn't be called inside a database transaction.
     *
     * @return the first response that wasn't a refusal, or else the last refusal
     * @throws IllegalArgumentException as for {@link RequestSigner#sign}
     * @throws IOException              as for {@link FederationHttpClient#post}
     */
    public Response post(URI inbox, byte[] body, SigningKey key) throws IOException {
        return signers.firstNotRefused(signer -> http.post(inbox, ACTIVITY_JSON, body, signer.sign("POST", inbox, body, key)));
    }
}
