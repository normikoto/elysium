package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The parts of Mastodon's Admin::Account entity that exist here. There are no email addresses, sign-up approvals, or stored
 * IP addresses to report, and a suspension is the only thing that can be done to an account.
 *
 * @param domain null for a local account
 * @param role   null for a remote account
 */
@Schema(name = "AdminAccount", description = """
        The parts of Mastodon's Admin::Account entity that exist here. There are no email addresses, IP addresses, or \
        sign-up approvals to report.""")
record AdminAccountResponse(
        String id,
        String username,
        @Schema(description = "Null for a local account.")
        String domain,
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
