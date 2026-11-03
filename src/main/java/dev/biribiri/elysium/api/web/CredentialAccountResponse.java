package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.List;

/**
 * Mastodon's CredentialAccount entity: an Account, plus the settings only its owner can see. Those settings don't exist
 * yet, so they hold the values a new account would have.
 */
record CredentialAccountResponse(@JsonUnwrapped AccountResponse account, Source source) {

    static CredentialAccountResponse from(AccountResponse account) {
        return new CredentialAccountResponse(account, new Source("public", false, "", "", List.of(), 0));
    }

    record Source(
            String privacy,
            boolean sensitive,
            String language,
            String note,
            List<Object> fields,
            @JsonProperty("follow_requests_count") int followRequestsCount
    ) {
    }
}
