package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;

/**
 * An item of a {@code tag} array, which can mix types.
 */
public sealed interface Tag permits Hashtag, Mention, QuoteLink {

    String type();

    URI href();

    String name();
}
