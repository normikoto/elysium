package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * FEP-044f's proof that a quote is approved, which other servers fetch to check a quote. It never embeds either status,
 * so that it tells whoever fetches it nothing that the quote's {@code Note} doesn't.
 *
 * @param attributedTo      the quoted author, who approved it
 * @param interactingObject the quote
 * @param interactionTarget the quoted status
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteAuthorization(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI attributedTo,
        URI interactingObject,
        URI interactionTarget
) {

    static final List<Object> CONTEXT = List.of(ActivityStreams.CONTEXT, Map.of(
            "QuoteAuthorization", "https://w3id.org/fep/044f#QuoteAuthorization",
            "gts", "https://gotosocial.org/ns#",
            "interactingObject", Map.of("@id", "gts:interactingObject", "@type", "@id"),
            "interactionTarget", Map.of("@id", "gts:interactionTarget", "@type", "@id")));

    public static QuoteAuthorization of(URI id, URI quotedAuthor, URI quote, URI quoted) {
        return new QuoteAuthorization(CONTEXT, id, "QuoteAuthorization", quotedAuthor, quote, quoted);
    }

    /**
     * Where a local author's approvals are served.
     */
    public static URI idFor(URI quotedAuthor, java.util.UUID stampId) {
        return URI.create(quotedAuthor + "/quote_authorizations/" + stampId);
    }

    /**
     * The {@code Delete} that withdraws an approval, as FEP-044f says, embedding neither status.
     */
    public static Delete deleteOf(URI id, URI quotedAuthor, List<String> to) {
        return new Delete(ActivityStreams.CONTEXT, URI.create(id + "#delete"), "Delete", quotedAuthor, to, List.of(),
                Map.of("id", id.toString(), "type", "QuoteAuthorization"));
    }
}
