package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.inbox.NotificationGroup;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "NotificationGroup")
@JsonInclude(JsonInclude.Include.NON_NULL)
record NotificationGroupResponse(
        @JsonProperty("group_key") String groupKey,
        @JsonProperty("notifications_count") long notificationsCount,
        String type,
        @JsonProperty("most_recent_notification_id") String mostRecentNotificationId,
        @JsonProperty("page_min_id") String pageMinId,
        @JsonProperty("page_max_id") String pageMaxId,
        @JsonProperty("latest_page_notification_at") String latestPageNotificationAt,
        @JsonProperty("sample_account_ids") List<String> sampleAccountIds,
        @JsonProperty("status_id") String statusId) {
    static NotificationGroupResponse from(NotificationGroup group, List<String> accounts) {
        return new NotificationGroupResponse(group.groupKey(), group.notificationsCount(), group.type(),
                group.mostRecentNotificationId().toString(), group.pageMinId() == null ? null : group.pageMinId().toString(),
                group.pageMaxId() == null ? null : group.pageMaxId().toString(),
                group.latestPageNotificationAt() == null ? null : ApiTimestamps.format(group.latestPageNotificationAt()),
                accounts, group.statusId() == null ? null : group.statusId().toString());
    }
}
