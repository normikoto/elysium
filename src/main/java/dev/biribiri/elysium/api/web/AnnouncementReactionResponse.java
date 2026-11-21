package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.announcement.AnnouncementReaction;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's Reaction entity, for announcements.
 */
@Schema(name = "Reaction", description = """
        Mastodon's Reaction entity: everyone's reactions to an announcement with one emoji. `name` is the Unicode emoji, \
        or a custom emoji's shortcode. A custom emoji has `url` and `static_url`.""")
@JsonInclude(JsonInclude.Include.NON_NULL)
record AnnouncementReactionResponse(
        String name,
        long count,
        boolean me,
        String url,
        @JsonProperty("static_url") String staticUrl
) {

    static AnnouncementReactionResponse of(AnnouncementReaction reaction, InstanceProperties instance) {
        var emoji = reaction.emoji();
        return new AnnouncementReactionResponse(
                reaction.name(),
                reaction.count(),
                reaction.me(),
                emoji == null ? null : emoji.url(instance).toString(),
                emoji == null ? null : emoji.staticUrl(instance).toString());
    }
}
