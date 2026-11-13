package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.StatusVersion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's StatusEdit entity. Emoji don't exist yet, so {@code emojis} is always empty.
 */
@Schema(name = "StatusEdit", description = """
        Mastodon's StatusEdit entity: one version of a status. Only the current version has its \
        `media_attachments`, since earlier ones don't keep theirs. `poll` only has the options a version's poll had, \
        and is null for one without a poll, or from before the upgrade that added polls. Emoji don't exist yet, so \
        `emojis` is always empty.""")
record StatusEditResponse(
        String content,
        @JsonProperty("spoiler_text") String spoilerText,
        boolean sensitive,
        @JsonProperty("created_at") String createdAt,
        AccountResponse account,
        PollResponse.Version poll,
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
                version.pollOptions() == null ? null : new PollResponse.Version(version.pollOptions().stream()
                        .map(PollResponse.VersionOption::new).toList()),
                mediaAttachments,
                List.of());
    }
}
