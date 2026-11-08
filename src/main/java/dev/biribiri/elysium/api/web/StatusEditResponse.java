package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.StatusVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's StatusEdit entity. Media, polls, and emoji don't exist yet, so those fields hold what a version without any of
 * them would have.
 */
@Schema(name = "StatusEdit", description = """
        Mastodon's StatusEdit entity: one version of a status. Media, polls, and emoji don't exist yet, so \
        `media_attachments` and `emojis` are always empty, and `poll` is null.""")
record StatusEditResponse(
        String content,
        @JsonProperty("spoiler_text") String spoilerText,
        boolean sensitive,
        @JsonProperty("created_at") String createdAt,
        AccountResponse account,
        Object poll,
        @JsonProperty("media_attachments") List<Object> mediaAttachments,
        List<Object> emojis
) {

    static StatusEditResponse from(StatusVersion version, AccountResponse account) {
        return new StatusEditResponse(
                version.content(),
                version.spoilerText(),
                version.sensitive(),
                ApiTimestamps.format(version.since()),
                account,
                null,
                List.of(),
                List.of());
    }
}
