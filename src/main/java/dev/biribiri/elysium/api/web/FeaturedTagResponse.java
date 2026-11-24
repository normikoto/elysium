package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.FeaturedTag;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZoneOffset;

/**
 * Mastodon's FeaturedTag entity, with its count as a string and its date without a time, as Mastodon sends them, though
 * its examples show otherwise.
 *
 * @param url the tag's page here, since profile pages can't be narrowed to one tag, as Mastodon's URL is
 */
@Schema(name = "FeaturedTag", description = """
        Mastodon's FeaturedTag entity. `name` is normalized: lowercase, and in Unicode's NFKC form. `url` is the \
        tag's page on this server, with everyone's public statuses, rather than the account's profile narrowed to \
        the tag.""")
record FeaturedTagResponse(
        String id,
        String name,
        String url,
        @Schema(description = "How many of the account's public and unlisted statuses have the tag, as a string.")
        @JsonProperty("statuses_count") String statusesCount,
        @Schema(description = "The day the newest of them was posted, in UTC, as `YYYY-MM-DD`, or null if none has.",
                nullable = true)
        @JsonProperty("last_status_at") String lastStatusAt) {

    static FeaturedTagResponse from(FeaturedTag tag, InstanceProperties instance) {
        return new FeaturedTagResponse(
                tag.id().toString(),
                tag.name(),
                Hashtags.url(instance.baseUrl(), tag.name()).toString(),
                Long.toString(tag.statusesCount()),
                tag.lastStatusAt() == null ? null : tag.lastStatusAt().atOffset(ZoneOffset.UTC).toLocalDate().toString());
    }
}
