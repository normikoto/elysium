package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's Status::Tag entity.
 *
 * @param url this server's page for the tag, even on a status from another server, as on Mastodon
 */
@Schema(name = "StatusTag", description = """
        Mastodon's Status::Tag entity. `name` is normalized: lowercase, and in Unicode's NFKC form. `url` is this \
        server's page for the tag, even on a status from another server.""")
record StatusTagResponse(String name, String url) {

    static StatusTagResponse of(String name, InstanceProperties instance) {
        return new StatusTagResponse(name, Hashtags.url(instance.baseUrl(), name).toString());
    }
}
