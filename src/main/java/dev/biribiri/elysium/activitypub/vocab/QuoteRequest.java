package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the author of a status on another server to approve a quote of it, as FEP-044f has it.
 *
 * @param object     the quoted status
 * @param instrument the quote, embedded so that its author's server can look at it without fetching it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteRequest(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI actor,
        List<String> to,
        URI object,
        Object instrument
) {

    static final List<Object> CONTEXT = List.of(ActivityStreams.CONTEXT, terms());

    private static Map<String, Object> terms() {
        var terms = new LinkedHashMap<>(Note.TERMS);
        terms.put("QuoteRequest", "https://w3id.org/fep/044f#QuoteRequest");
        return terms;
    }

    /**
     * @param id the request's {@code id}, which stays the same for each quote, so that sending it again is a repeat
     */
    public static QuoteRequest of(URI id, Note quote, URI quoted, URI quotedAuthor) {
        return new QuoteRequest(CONTEXT, id, "QuoteRequest", quote.attributedTo(), List.of(quotedAuthor.toString()), quoted,
                quote.withoutContext());
    }
}
