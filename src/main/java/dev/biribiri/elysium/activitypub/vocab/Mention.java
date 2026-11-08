package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;

/**
 * A {@code tag} naming an account that a status mentions. Mastodon notifies an account mentioned this way, while one that's
 * only addressed sees the status without being told about it.
 *
 * @param name the account's full address, such as {@code @alice@example.com}, which is what Mastodon sends
 */
public record Mention(String type, URI href, String name) {

    public static Mention of(URI href, String name) {
        return new Mention("Mention", href, name);
    }
}
