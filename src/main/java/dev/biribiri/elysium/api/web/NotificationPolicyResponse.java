package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.notification.NotificationPolicy;
import dev.biribiri.elysium.notification.NotificationRequest;

record NotificationPolicyResponse(
        @JsonProperty("for_not_following") String forNotFollowing,
        @JsonProperty("for_not_followers") String forNotFollowers,
        @JsonProperty("for_new_accounts") String forNewAccounts,
        @JsonProperty("for_private_mentions") String forPrivateMentions,
        @JsonProperty("for_limited_accounts") String forLimitedAccounts,
        @JsonProperty("for_bots") String forBots,
        Summary summary) {

    static NotificationPolicyResponse from(NotificationPolicy policy, NotificationRequest.Summary summary) {
        return new NotificationPolicyResponse(policy.forNotFollowing().value(), policy.forNotFollowers().value(),
                policy.forNewAccounts().value(), policy.forPrivateMentions().value(), policy.forLimitedAccounts().value(),
                policy.forBots().value(), new Summary(summary.requests(), summary.notifications()));
    }

    record Summary(@JsonProperty("pending_requests_count") long pendingRequestsCount,
                   @JsonProperty("pending_notifications_count") long pendingNotificationsCount) {
    }
}
