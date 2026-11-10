package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.StatusVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's StatusEdit entity. Polls and emoji don't exist yet, so those fields hold what a version without any of them
 * would have.
 */
@Schema(name = "StatusEdit", description = """
        Mastodon's StatusEdit entity: one version of a status. Only the current version has its \
        `media_attachments`, since earlier ones don't keep theirs. Polls and emoji don't exist yet, so `emojis` is always \
        empty, and `poll` is null.""")
record StatusEditResponse(
        String content,
        @JsonProperty("spoiler_text") String spoilerText,
        boolean sensitive,
        @JsonProperty("created_at") String createdAt,
        AccountResponse account,
        Object poll,
        @JsonProperty("media_attachments") List<MediaAttachmentResponse> mediaAttachments,
        List<Object> emojis
) {

    static StatusEditResponse from(
            StatusVersion version, AccountResponse account, List<MediaAttachmentResponse> mediaAttachments) {
        return new StatusEditResponse(
                version.content(),
                version.spoilerText(),
                version.sensitive(),
                ApiTimestamps.format(version.since()),
                account,
                null,
                mediaAttachments,
                List.of());
    }
}
