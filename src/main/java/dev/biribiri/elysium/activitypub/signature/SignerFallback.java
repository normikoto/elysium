package dev.biribiri.elysium.activitypub.signature;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * There's no way to tell which signature formats a server accepts before asking, so this tries each {@link RequestSigner}
 * in order, and moves on to the next one if the server refuses the request.
 *
 * <p>A 400 counts as a refusal because Mastodon answers a signature header it can't read with one, and retries on it
 * itself. A 400 about something else only costs one more request.
 */
final class SignerFallback {

    private static final Set<Integer> REFUSALS = Set.of(400, 401, 403);

    private final List<RequestSigner> signers;

    SignerFallback(List<RequestSigner> signers) {
        if (signers.isEmpty()) {
            throw new IllegalArgumentException("At least one signer is needed");
        }
        this.signers = List.copyOf(signers);
    }

    /**
     * @param attempt signs and makes the request. It's signed for each try, so that the time in a signature is never out of
     *                date.
     * @return the first response that wasn't a refusal, or else the last refusal
     */
    <E extends Exception> Response firstNotRefused(Attempt<E> attempt) throws IOException, E {
        Response response = null;
        for (var signer : signers) {
            response = attempt.send(signer);
            if (!REFUSALS.contains(response.status())) {
                return response;
            }
        }
        return response;
    }

    interface Attempt<E extends Exception> {
        Response send(RequestSigner signer) throws IOException, E;
    }
}
