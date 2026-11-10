package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.BoostStats;
import dev.biribiri.elysium.content.LikeStats;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mastodon's Status entity. Polls don't exist yet, so those fields hold the values a status without one would have.
 *
 * @param text the source text, which is only included when a status is deleted, so that clients can offer to redraft it
 */
@Schema(name = "Status", description = """
        Mastodon's Status entity. Polls and cards don't exist yet, so those fields \
        are always null. A boost has the boosted status in `reblog`, and no content of its own. \
        `reblogs_count` and `favourites_count` only count what this server knows of. `mentions` leaves out accounts that are suspended or deleted, and those \
        a status from another server was sent to without mentioning them. `edited_at` is when the status was last \
        edited, or null if it never was.""")
record StatusResponse(
        String id,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("edited_at") String editedAt,
        @JsonProperty("in_reply_to_id") String inReplyToId,
        @JsonProperty("in_reply_to_account_id") String inReplyToAccountId,
        boolean sensitive,
        @JsonProperty("spoiler_text") String spoilerText,
        String visibility,
        String language,
        String uri,
        String url,
        @JsonProperty("replies_count") long repliesCount,
        @JsonProperty("reblogs_count") long reblogsCount,
        @JsonProperty("favourites_count") long favouritesCount,
        String content,
        @Schema(description = "The source text, only when the status has just been deleted.")
        @JsonInclude(JsonInclude.Include.NON_NULL) String text,
        @Schema(implementation = Object.class, nullable = true, description = "The boosted status, for a boost.")
        StatusResponse reblog,
        Object application,
        AccountResponse account,
        @JsonProperty("media_attachments") List<MediaAttachmentResponse> mediaAttachments,
        List<MentionResponse> mentions,
        List<StatusTagResponse> tags,
        List<Object> emojis,
        Object card,
        Object poll,
        boolean favourited,
        boolean reblogged,
        boolean muted,
        boolean bookmarked,
        boolean pinned
) {

    /**
     * @param reblog the boosted status, for a boost, or null
     */
    static StatusResponse from(
            Status status, AccountResponse account, List<MentionResponse> mentions, List<StatusTagResponse> tags,
            List<MediaAttachmentResponse> mediaAttachments, boolean includeText, LikeStats likes, BoostStats boosts, StatusResponse reblog) {
        return new StatusResponse(
                status.id().toString(),
                ApiTimestamps.format(status.publishedAt()),
                status.editedAt() == null ? null : ApiTimestamps.format(status.editedAt()),
                string(status.inReplyToId()),
                string(status.inReplyToAccountId()),
                status.sensitive(),
                status.spoilerText(),
                status.visibility().value(),
                status.language(),
                status.uri().toString(),
                pageUrl(status),
                status.repliesCount(),
                boosts.count(),
                likes.count(),
                status.content(),
                includeText ? status.text() : null,
                reblog,
                null,
                account,
                mediaAttachments,
                mentions,
                tags,
                List.of(),
                null,
                null,
                likes.favourited(),
                boosts.reblogged(),
                false,
                false,
                false);
    }

    /**
     * A status from another server links to the page it gave. Web pages here always read as an anonymous viewer, so a local
     * status that anyone can't see has no page to link to.
     */
    private static String pageUrl(Status status) {
        if (status.isReblog()) {
            return null;
        }
        if (status.url() != null) {
            return status.url().toString();
        }
        return status.isVisibleTo(Optional.empty()) ? status.uri().toString() : null;
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }
}
