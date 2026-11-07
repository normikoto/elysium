package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.biribiri.elysium.actor.Profile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's CredentialAccount entity: an Account, plus what its owner needs to edit it. The posting settings in
 * {@code source} don't exist yet, so they hold the values a new account would have.
 */
@Schema(name = "CredentialAccount", description = """
        Mastodon's CredentialAccount entity: an Account, plus `source`, which has the note and fields as they were \
        written. The settings in `source` don't exist yet, so they have the values a new account would have.""")
record CredentialAccountResponse(@JsonUnwrapped AccountResponse account, Source source) {

    static CredentialAccountResponse from(AccountResponse account, Profile profile) {
        var fields = profile.fields().stream().map(field -> AccountResponse.Field.of(field, field.value())).toList();
        return new CredentialAccountResponse(account, new Source("public", false, "", profile.note(), fields, 0));
    }

    /**
     * @param note   as it was written, rather than as HTML
     * @param fields with their values as they were written
     */
    record Source(
            String privacy,
            boolean sensitive,
            String language,
            String note,
            List<AccountResponse.Field> fields,
            @JsonProperty("follow_requests_count") int followRequestsCount
    ) {
    }
}
