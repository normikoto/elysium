package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Mastodon's V2 Instance entity, with what this server has. Clients read {@code api_versions} from it to decide which
 * newer parts of the API to use.
 */
@Schema(name = "InstanceV2", description = """
        Mastodon's Instance entity. There's no thumbnail, contact account, or rules, and `usage.users.active_month` \
        counts every local account.""")
record InstanceV2Response(
        String domain,
        String title,
        String version,
        @JsonProperty("source_url") String sourceUrl,
        String description,
        Usage usage,
        Object thumbnail,
        List<Object> icon,
        List<String> languages,
        Configuration configuration,
        Registrations registrations,
        @JsonProperty("api_versions") Map<String, Integer> apiVersions,
        Contact contact,
        List<Object> rules
) {

    record Usage(Users users) {
    }

    record Users(@JsonProperty("active_month") long activeMonth) {
    }

    record Configuration(
            Map<String, String> urls,
            InstanceResponse.Accounts accounts,
            InstanceResponse.Statuses statuses,
            @JsonProperty("media_attachments") InstanceResponse.MediaAttachments mediaAttachments,
            InstanceResponse.Polls polls,
            Map<String, Boolean> translation) {
    }

    record Registrations(boolean enabled, @JsonProperty("approval_required") boolean approvalRequired, String message) {
    }

    record Contact(String email, Object account) {
    }
}
