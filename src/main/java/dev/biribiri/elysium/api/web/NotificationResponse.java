package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Notification", description = "An account notification.  Currently only mention, follow, follow_request, favourite, reblog, quote, quoted_update, poll, admin.report, and Pleroma's pleroma:emoji_reaction events are generated.")
record NotificationResponse(
        String id, String type, @JsonProperty("created_at") String createdAt, AccountResponse account,
        @JsonInclude(JsonInclude.Include.NON_NULL) StatusResponse status,
        @JsonProperty("group_key") String groupKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) ReportResponse report,
        @Schema(description = "For `pleroma:emoji_reaction`, the emoji, named as in an EmojiReaction.")
        @JsonInclude(JsonInclude.Include.NON_NULL) String emoji,
        @Schema(description = "For `pleroma:emoji_reaction` with a custom emoji, its image.")
        @JsonProperty("emoji_url") @JsonInclude(JsonInclude.Include.NON_NULL) String emojiUrl) {
}
