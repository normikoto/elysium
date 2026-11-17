package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Status;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's StatusSource entity, which clients fill an edit form with.
 */
@Schema(name = "StatusSource", description = "Mastodon's StatusSource entity: what the author wrote, for editing it.")
record StatusSourceResponse(
        String id,
        String text,
        @JsonProperty("spoiler_text") String spoilerText,
        @Schema(allowableValues = {"text/plain", "text/markdown"}, description = """
                What the text is written in, as glitch-soc and Pleroma give it. Mastodon doesn't send this.""")
        @JsonProperty("content_type") String contentType) {

    static StatusSourceResponse from(Status status) {
        return new StatusSourceResponse(
                status.id().toString(), status.text(), status.spoilerText(), status.contentType().value());
    }
}
