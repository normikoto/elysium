package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.activitypub.signature.Digests;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * An {@code Accept} or {@code Reject} of another server's {@code QuoteRequest}, which includes the request, as for a
 * {@code Follow}.
 *
 * @param result the {@link QuoteAuthorization} for an {@code Accept}, or null for a {@code Reject}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteRequestAnswer(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        Request object,
        URI result
) {

    private static final List<Object> CONTEXT = List.of(ActivityStreams.CONTEXT,
            Map.of("QuoteRequest", "https://w3id.org/fep/044f#QuoteRequest"));

    /**
     * @param instrument the quote's ID, since the request's copy of it is the other server's to send
     */
    public record Request(String id, String type, URI actor, URI object, URI instrument) {
    }

    /**
     * The {@code id} comes from the request's, hashed because it came from another server, as for a {@code Follow}.
     *
     * @param quotedAuthor the local author of the quoted status, who sends this
     */
    public static QuoteRequestAnswer accept(
            URI quotedAuthor, String requestId, URI requester, URI quoted, URI quote, URI authorization) {
        return new QuoteRequestAnswer(CONTEXT, URI.create(quotedAuthor + "#accepts/quote_requests/"
                + Digests.sha256Hex(requestId)), "Accept", quotedAuthor, List.of(requester.toString()),
                new Request(requestId, "QuoteRequest", requester, quoted, quote), authorization);
    }

    public static QuoteRequestAnswer reject(URI quotedAuthor, String requestId, URI requester, URI quoted, URI quote) {
        return new QuoteRequestAnswer(CONTEXT, URI.create(quotedAuthor + "#rejects/quote_requests/"
                + Digests.sha256Hex(requestId)), "Reject", quotedAuthor, List.of(requester.toString()),
                new Request(requestId, "QuoteRequest", requester, quoted, quote), null);
    }
}
