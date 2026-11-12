package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Notification", description = "An account notification. Currently only mention, follow, follow_request, favourite, reblog, quote, quoted_update, and admin.report events are generated.")
record NotificationResponse(
        String id, String type, @JsonProperty("created_at") String createdAt, AccountResponse account,
        @JsonInclude(JsonInclude.Include.NON_NULL) StatusResponse status,
        @JsonProperty("group_key") String groupKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) ReportResponse report) {
}
