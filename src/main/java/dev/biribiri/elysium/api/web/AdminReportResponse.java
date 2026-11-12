package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "AdminReport", description = """
        Mastodon's Admin::Report entity. `rules` is always empty, since this server has no rules, and `statuses` leaves \
        out statuses that have been deleted since. For a report from another server, `account` is whoever sent it, \
        usually that server's instance actor.""")
record AdminReportResponse(
        String id,
        @JsonProperty("action_taken") boolean actionTaken,
        @JsonProperty("action_taken_at") String actionTakenAt,
        @Schema(allowableValues = {"spam", "legal", "other"}) String category,
        String comment,
        boolean forwarded,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        AdminAccountResponse account,
        @JsonProperty("target_account") AdminAccountResponse targetAccount,
        @Schema(description = "Null if no admin is handling it.")
        @JsonProperty("assigned_account") AdminAccountResponse assignedAccount,
        @Schema(description = "Null while it's open, or once that admin's account is gone.")
        @JsonProperty("action_taken_by_account") AdminAccountResponse actionTakenByAccount,
        List<StatusResponse> statuses,
        List<Object> rules) {
}
