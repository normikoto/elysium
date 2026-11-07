package dev.biribiri.elysium.api.web;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Context", description = "Mastodon's Context entity: the statuses around one in its thread.")
record ContextResponse(
        @Schema(description = "The statuses it replies to, oldest first.") List<StatusResponse> ancestors,
        @Schema(description = "The replies to it and their replies, depth first.") List<StatusResponse> descendants) {
}
