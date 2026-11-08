package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The parts of Mastodon's Admin::Account entity that exist here. There are no stored IP addresses to report, and a suspension
 * is the only thing that can be done to an account. Sign-ups waiting for approval aren't accounts yet, so they aren't
 * listed.
 *
 * @param domain null for a local account
 * @param email  null for an account that didn't give one
 * @param role   null for a remote account
 */
@Schema(name = "AdminAccount", description = """
        The parts of Mastodon's Admin::Account entity that exist here. There are no IP addresses to report. Sign-ups \
        waiting for approval aren't listed.""")
record AdminAccountResponse(
        String id,
        String username,
        @Schema(description = "Null for a local account.")
        String domain,
        @Schema(description = "Null for an account that signed up with an invite, was made with a command, or is on another "
                + "server, since only accounts that confirmed an address have one.")
        String email,
        @JsonProperty("created_at") String createdAt,
        @Schema(description = "Null for an account on another server.")
        Role role,
        boolean suspended,
        AccountResponse account
) {

    @Schema(name = "AdminAccountRole", description = "Only has a `name`.")
    record Role(@Schema(allowableValues = {"Admin", "User"}) String name) {
    }
}
