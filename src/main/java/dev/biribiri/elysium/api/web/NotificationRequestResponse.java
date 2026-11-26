package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param notificationsCount a string, as Mastodon sends it
 * @param lastStatus         null once the newest status can't be seen
 */
record NotificationRequestResponse(
        String id,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        AccountResponse account,
        @JsonProperty("notifications_count") String notificationsCount,
        @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("last_status") StatusResponse lastStatus) {
}
