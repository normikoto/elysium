package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.actor.signing.SigningKey;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Sends activities to other servers. As with {@link SignedFetcher}, there's no way to tell which signature formats a server
 * accepts before asking, so this tries each {@link RequestSigner} in order until the server stops refusing.
 */
@Component
public class SignedSender {

    private static final String ACTIVITY_JSON = "application/activity+json";

    private final FederationHttpClient http;
    private final List<RequestSigner> signers;

    SignedSender(FederationHttpClient http, List<RequestSigner> signers) {
        if (signers.isEmpty()) {
            throw new IllegalArgumentException("At least one signer is needed");
        }
        this.http = http;
        this.signers = List.copyOf(signers);
    }

    /**
     * Blocks while sending, so this mustn't be called inside a database transaction.
     *
     * @return the first response that wasn't a refusal, or else the last refusal
     * @throws IllegalArgumentException as for {@link RequestSigner#sign}
     * @throws IOException              as for {@link FederationHttpClient#post}
     */
    public Response post(URI inbox, byte[] body, SigningKey key) throws IOException {
        Response response = null;
        for (var signer : signers) {
            // Signed for each try, so that the time in a signature is never out of date.
            response = http.post(inbox, ACTIVITY_JSON, body, signer.sign("POST", inbox, body, key));
            if (!SignedFetcher.isRefusal(response)) {
                return response;
            }
        }
        return response;
    }
}
