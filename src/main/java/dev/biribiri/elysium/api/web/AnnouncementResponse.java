package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's Announcement entity.
 */
@Schema(name = "Announcement", description = """
        Mastodon's Announcement entity. `content` is HTML made from the admin's plain text, with links and hashtags \
        linked. `mentions` and `statuses` are always empty. `starts_at` and `ends_at` are both null or both set. \
        `published_at` is when it was last published.""")
record AnnouncementResponse(
        String id,
        String content,
        @JsonProperty("starts_at") String startsAt,
        @JsonProperty("ends_at") String endsAt,
        boolean published,
        @JsonProperty("all_day") boolean allDay,
        @JsonProperty("published_at") String publishedAt,
        @JsonProperty("updated_at") String updatedAt,
        @Schema(description = "Whether the user has dismissed it.") boolean read,
        List<Object> mentions,
        List<Object> statuses,
        List<StatusTagResponse> tags,
        List<CustomEmojiResponse> emojis,
        List<AnnouncementReactionResponse> reactions
) {
}
