package dev.biribiri.elysium.activitypub.vocab;

/**
 * An item of a {@code tag} array, which can mix types.
 */
public sealed interface Tag permits Emoji, Hashtag, Mention, QuoteLink {

    String type();

    String name();
}
