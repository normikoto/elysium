package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.graph.AccountList;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's List entity.
 */
@Schema(name = "List")
record ListResponse(
        String id,
        String title,
        @JsonProperty("replies_policy") @Schema(allowableValues = {"followed", "list", "none"}) String repliesPolicy,
        @Schema(description = "Whether its members' statuses are left out of the home timeline.") boolean exclusive) {

    static ListResponse from(AccountList list) {
        return new ListResponse(list.id().toString(), list.title(), list.repliesPolicy().value(), list.exclusive());
    }
}
