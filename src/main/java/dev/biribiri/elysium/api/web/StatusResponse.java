package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Status;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mastodon's Status entity. Likes, boosts, media, polls, mentions, and hashtags don't exist yet, so those fields hold the
 * values a status without any of them would have.
 *
 * @param text the source text, which is only included when a status is deleted, so that clients can offer to redraft it
 */
@Schema(name = "Status", description = """
        Mastodon's Status entity. Favourites, boosts, media, polls, cards, mentions, and hashtags don't exist yet, so \
        those fields are always empty, null, zero, or false. Only statuses from other servers can have been edited, \
        so `edited_at` is always null for a local one.""")
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
        @JsonProperty("reblogs_count") int reblogsCount,
        @JsonProperty("favourites_count") int favouritesCount,
        String content,
        @Schema(description = "The source text, only when the status has just been deleted.")
        @JsonInclude(JsonInclude.Include.NON_NULL) String text,
        Object reblog,
        Object application,
        AccountResponse account,
        @JsonProperty("media_attachments") List<Object> mediaAttachments,
        List<Object> mentions,
        List<Object> tags,
        List<Object> emojis,
        Object card,
        Object poll,
        boolean favourited,
        boolean reblogged,
        boolean muted,
        boolean bookmarked,
        boolean pinned
) {

    static StatusResponse from(Status status, AccountResponse account, boolean includeText) {
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
                0,
                0,
                status.content(),
                includeText ? status.text() : null,
                null,
                null,
                account,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                false,
                false,
                false,
                false,
                false);
    }

    /**
     * A status from another server links to the page it gave. Web pages here always read as an anonymous viewer, so a local
     * status that anyone can't see has no page to link to.
     */
    private static String pageUrl(Status status) {
        if (status.url() != null) {
            return status.url().toString();
        }
        return status.isVisibleTo(Optional.empty()) ? status.uri().toString() : null;
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }
}
