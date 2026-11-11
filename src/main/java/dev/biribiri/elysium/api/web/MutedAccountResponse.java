package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An account in the list of those the user has muted, with when the mute ends, as Mastodon adds.
 *
 * @param muteExpiresAt when the mute ends, or null if it doesn't
 */
@Schema(name = "MutedAccount", description = "Mastodon's Account entity, with `mute_expires_at`.")
record MutedAccountResponse(
        @JsonUnwrapped AccountResponse account,
        @Schema(description = "When the mute ends, or null if it doesn't.")
        @JsonProperty("mute_expires_at") String muteExpiresAt) {
}
