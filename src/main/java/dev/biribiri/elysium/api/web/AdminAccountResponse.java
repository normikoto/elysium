package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The parts of Mastodon's Admin::Account entity that exist here. There are no email addresses, sign-up approvals, or stored
 * IP addresses to report, and a suspension is the only thing that can be done to an account.
 *
 * @param domain null for a local account
 * @param role   null for a remote account
 */
record AdminAccountResponse(
        String id,
        String username,
        String domain,
        @JsonProperty("created_at") String createdAt,
        Role role,
        boolean suspended,
        AccountResponse account
) {

    record Role(String name) {
    }
}
