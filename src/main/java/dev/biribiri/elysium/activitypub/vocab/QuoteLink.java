package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;

/**
 * FEP-e232's link to the status that a status quotes, which Misskey and others read when they don't know FEP-044f.
 */
public record QuoteLink(String type, String mediaType, String rel, URI href, String name) implements Tag {

    public static final String REL = "https://misskey-hub.net/ns#_misskey_quote";

    public static QuoteLink of(URI quoted) {
        return new QuoteLink("Link", ActivityStreams.LD_JSON, REL, quoted, "RE: " + quoted);
    }
}
