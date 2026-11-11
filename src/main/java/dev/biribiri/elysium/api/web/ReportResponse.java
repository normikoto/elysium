package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.moderation.Report;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(name = "Report", description = """
        Mastodon's Report entity. `rule_ids` is always null, since this server has no rules, and `status_ids` leaves out \
        statuses that have been deleted since.""")
record ReportResponse(
        String id,
        @JsonProperty("action_taken") boolean actionTaken,
        @JsonProperty("action_taken_at") String actionTakenAt,
        @Schema(allowableValues = {"spam", "legal", "other"}) String category,
        String comment,
        boolean forwarded,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("status_ids") List<String> statusIds,
        @JsonProperty("rule_ids") List<String> ruleIds,
        @JsonProperty("target_account") AccountResponse targetAccount) {

    static ReportResponse from(Report report, AccountResponse target) {
        return new ReportResponse(
                report.id().toString(),
                report.isResolved(),
                report.resolvedAt() == null ? null : ApiTimestamps.format(report.resolvedAt()),
                report.category().value(),
                report.comment(),
                report.forwarded(),
                ApiTimestamps.format(report.createdAt()),
                report.statusIds().stream().map(UUID::toString).toList(),
                null,
                target);
    }
}
