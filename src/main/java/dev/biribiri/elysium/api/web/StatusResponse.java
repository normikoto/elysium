package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mastodon's Status entity. Likes, boosts, media, polls, mentions, and hashtags don't exist yet, so those fields hold the
 * values a status without any of them would have.
 *
 * @param text the source text, which is only included when a status is deleted, so that clients can offer to redraft it
 */
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
                null,
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
     * Web pages always read as an anonymous viewer, so other statuses have no page to link to.
     */
    private static String pageUrl(Status status) {
        return status.isVisibleTo(Optional.empty()) ? status.uri().toString() : null;
    }

    private static String string(UUID id) {
        return id == null ? null : id.toString();
    }
}
