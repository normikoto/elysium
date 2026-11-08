package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "GroupedNotificationsResults")
record GroupedNotificationsResponse(
        List<AccountResponse> accounts,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("partial_accounts") List<PartialAccount> partialAccounts,
        List<StatusResponse> statuses,
        @JsonProperty("notification_groups") List<NotificationGroupResponse> notificationGroups) {

    @Schema(name = "PartialAccountWithAvatar")
    record PartialAccount(String id, String acct, boolean locked, boolean bot, String url, String avatar,
                          @JsonProperty("avatar_static") String avatarStatic,
                          @JsonProperty("avatar_description") String avatarDescription) {
        static PartialAccount from(AccountResponse account) {
            return new PartialAccount(account.id(), account.acct(), account.locked(), account.bot(), account.url(),
                    account.avatar(), account.avatarStatic(), null);
        }
    }
}
