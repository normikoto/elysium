package dev.biribiri.elysium.activitypub.vocab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.Visibility;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ActivityPub object for a status.
 *
 * @param updated    when it was last edited, or null if it never was, which tells a server that gets two {@code Update}s
 *                   which is newer
 * @param summary    the content warning as plain text, or null for none. ActivityStreams allows HTML here, but Mastodon
 *                   sends and reads this as plain text, so escaping it would show the escapes in its content warnings.
 * @param inReplyTo  the parent status, or null if this isn't a reply or the parent can't be shown
 * @param contentMap the content under its language, or null if no language is set
 * @param tag        the accounts it mentions, then its hashtags, or null if it has neither
 * @param attachment its attachments, in order, or null if it has none
 * @param quote              the status it quotes, as FEP-044f has it, or null if it doesn't quote one
 * @param quoteUri           the same, for servers that read Fedibird's term, once the quote is approved
 * @param quoteUrl           the same, for servers that read Misskey's other term, once the quote is approved
 * @param misskeyQuote       the same, for servers that read Misskey's term, once the quote is approved
 * @param quoteAuthorization the quoted author's {@code QuoteAuthorization}, or null if there's none to show
 * @param interactionPolicy  who can quote it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Note(
        @JsonProperty("@context") List<Object> context,
        URI id,
        String type,
        URI attributedTo,
        URI url,
        String published,
        String updated,
        List<String> to,
        List<String> cc,
        String content,
        boolean sensitive,
        String summary,
        URI inReplyTo,
        Map<String, String> contentMap,
        List<Tag> tag,
        List<Document> attachment,
        URI likes,
        URI shares,
        URI quote,
        URI quoteUri,
        URI quoteUrl,
        @JsonProperty("_misskey_quote") URI misskeyQuote,
        URI quoteAuthorization,
        InteractionPolicy interactionPolicy
) {

    /**
     * Declared before {@link #CONTEXT}, which reads it.
     */
    static final Map<String, Object> QUOTE_TERMS = quoteTerms();

    /**
     * Adds Mastodon's terms for attachments, and the terms for quotes, which aren't in the ActivityStreams vocabulary.
     */
    static final List<Object> CONTEXT = List.of(ActivityStreams.CONTEXT, terms());

    /**
     * Mastodon's terms, and those for quotes: FEP-044f's, the older ones that other servers read, and GoToSocial's for
     * interaction policies.
     */
    private static Map<String, Object> terms() {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("sensitive", "as:sensitive");
        terms.put(Hashtag.CONTEXT_TERM, Hashtag.CONTEXT_IRI);
        terms.put("toot", "http://joinmastodon.org/ns#");
        terms.put("blurhash", "toot:blurhash");
        terms.put("focalPoint", Map.of("@container", "@list", "@id", "toot:focalPoint"));
        terms.putAll(QUOTE_TERMS);
        terms.put("gts", "https://gotosocial.org/ns#");
        terms.put("interactionPolicy", Map.of("@id", "gts:interactionPolicy", "@type", "@id"));
        terms.put("canQuote", Map.of("@id", "gts:canQuote", "@type", "@id"));
        terms.put("automaticApproval", Map.of("@id", "gts:automaticApproval", "@type", "@id"));
        terms.put("manualApproval", Map.of("@id", "gts:manualApproval", "@type", "@id"));
        return Collections.unmodifiableMap(terms);
    }

    private static Map<String, Object> quoteTerms() {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("quote", Map.of("@id", "https://w3id.org/fep/044f#quote", "@type", "@id"));
        terms.put("quoteUri", "http://fedibird.com/ns#quoteUri");
        terms.put("quoteUrl", "as:quoteUrl");
        terms.put("_misskey_quote", "https://misskey-hub.net/ns#_misskey_quote");
        terms.put("quoteAuthorization", Map.of("@id", "https://w3id.org/fep/044f#quoteAuthorization", "@type", "@id"));
        return Collections.unmodifiableMap(terms);
    }

    /**
     * What a {@code Note} says about what it quotes.
     *
     * @param quoted        the quoted status
     * @param quotedAuthor  its author, who is addressed so that their server can see a followers-only quote
     * @param shown         whether the quote is approved, or needs no approval
     * @param authorization the {@code QuoteAuthorization} that approved it, or null if none is needed or given
     */
    public record Quote(URI quoted, URI quotedAuthor, boolean shown, URI authorization) {
    }

    /**
     * FEP-044f's {@code interactionPolicy}, which only says who can quote.
     */
    public record InteractionPolicy(CanQuote canQuote) {

        /**
         * Only automatic approval, as on Mastodon.
         */
        public static InteractionPolicy automatic(List<String> approved) {
            return new InteractionPolicy(new CanQuote(List.copyOf(approved)));
        }
    }

    public record CanQuote(List<String> automaticApproval) {
    }

    /**
     * A direct status is only addressed to the accounts it mentions, so its parent's author has to be mentioned to get it,
     * as on Mastodon.
     *
     * @param inReplyTo    the parent status, or null if there's none to show
     * @param parentAuthor who wrote the parent status, or null if there's none to show
     * @param mentions     the accounts the status mentions
     * @param hashtags     the status's hashtags
     * @param attachments  the status's attachments, in order
     */
    public static Note from(
            Status status, Actor author, URI inReplyTo, URI parentAuthor, List<Mention> mentions, List<Hashtag> hashtags,
            List<Document> attachments) {
        return from(status, author, inReplyTo, parentAuthor, mentions, hashtags, attachments, null,
                InteractionPolicy.automatic(List.of(author.uri().toString())));
    }

    /**
     * @param quote  what it quotes, or null if it doesn't
     * @param policy who can quote it
     */
    public static Note from(
            Status status, Actor author, URI inReplyTo, URI parentAuthor, List<Mention> mentions, List<Hashtag> hashtags,
            List<Document> attachments, Quote quote, InteractionPolicy policy) {
        var others = new ArrayList<String>();
        mentions.forEach(mention -> others.add(mention.href().toString()));
        if (parentAuthor != null && !parentAuthor.equals(author.uri()) && status.visibility() != Visibility.DIRECT) {
            others.add(parentAuthor.toString());
        }
        if (quote != null && quote.quotedAuthor() != null && !quote.quotedAuthor().equals(author.uri())) {
            others.add(quote.quotedAuthor().toString());
        }
        var audience = Audience.of(status.visibility(), author, others);
        var shownQuote = quote != null && quote.shown() ? quote.quoted() : null;

        return new Note(
                CONTEXT,
                status.uri(),
                "Note",
                author.uri(),
                // Browsers get the status's page from the same URL as this document.
                status.uri(),
                ActivityStreams.time(status.publishedAt()),
                status.editedAt() == null ? null : ActivityStreams.time(status.editedAt()),
                audience.to(),
                audience.cc(),
                status.content(),
                status.sensitive(),
                status.spoilerText().isEmpty() ? null : status.spoilerText(),
                inReplyTo,
                status.language() == null ? null : Map.of(status.language(), status.content()),
                tags(mentions, hashtags, shownQuote),
                attachments.isEmpty() ? null : List.copyOf(attachments),
                URI.create(status.uri() + "/likes"),
                URI.create(status.uri() + "/shares"),
                quote == null ? null : quote.quoted(),
                shownQuote,
                shownQuote,
                shownQuote,
                quote != null && quote.shown() ? quote.authorization() : null,
                policy);
    }

    private static List<Tag> tags(List<Mention> mentions, List<Hashtag> hashtags, URI quoted) {
        var tags = new ArrayList<Tag>(mentions);
        tags.addAll(hashtags);
        if (quoted != null) {
            tags.add(QuoteLink.of(quoted));
        }
        return tags.isEmpty() ? null : List.copyOf(tags);
    }

    /**
     * @return this object as part of an activity, which has the {@code @context} for both
     */
    public Note withoutContext() {
        return new Note(
                null, id, type, attributedTo, url, published, updated, to, cc, content, sensitive, summary, inReplyTo,
                contentMap, tag, attachment, likes, shares, quote, quoteUri, quoteUrl, misskeyQuote, quoteAuthorization,
                interactionPolicy);
    }

    /**
     * @param updated when it last changed, for an {@code Update} sent for something other than an edit
     */
    public Note withUpdated(java.time.Instant updated) {
        return new Note(
                context, id, type, attributedTo, url, published, ActivityStreams.time(updated), to, cc, content, sensitive,
                summary, inReplyTo, contentMap, tag, attachment, likes, shares, quote, quoteUri, quoteUrl, misskeyQuote,
                quoteAuthorization, interactionPolicy);
    }
}
