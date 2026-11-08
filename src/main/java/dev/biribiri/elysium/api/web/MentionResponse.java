package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's Status::Mention entity.
 *
 * @param acct the username for a local account, and the username and domain for a remote one, as {@link AccountResponse}
 *             has it
 */
@Schema(name = "Mention", description = "Mastodon's Status::Mention entity.")
record MentionResponse(String id, String username, String url, String acct) {

    static MentionResponse from(Actor actor, boolean local) {
        return new MentionResponse(
                actor.id().toString(),
                actor.username(),
                actor.uri().toString(),
                local ? actor.username() : actor.username() + "@" + actor.domain());
    }
}
