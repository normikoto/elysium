package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.AccountStats;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Account entity. Profiles and follows don't exist yet, so those fields hold the values a new, empty account
 * would have. Clients need real image URLs, so those point to a bundled placeholder.
 */
record AccountResponse(
        String id,
        String username,
        String acct,
        @JsonProperty("display_name") String displayName,
        boolean locked,
        boolean bot,
        boolean group,
        boolean discoverable,
        @JsonProperty("created_at") String createdAt,
        String note,
        String url,
        String uri,
        String avatar,
        @JsonProperty("avatar_static") String avatarStatic,
        String header,
        @JsonProperty("header_static") String headerStatic,
        @JsonProperty("followers_count") int followersCount,
        @JsonProperty("following_count") int followingCount,
        @JsonProperty("statuses_count") long statusesCount,
        @JsonProperty("last_status_at") String lastStatusAt,
        List<Object> emojis,
        List<Object> fields
) {

    private static final String PLACEHOLDER_IMAGE_PATH = "/images/missing.png";

    static AccountResponse from(Actor actor, AccountStats stats, InstanceProperties instance) {
        return from(actor, actor.username(), stats, instance);
    }

    /**
     * @param acct the username for a local account, and the username and domain for a remote one
     */
    static AccountResponse from(Actor actor, String acct, AccountStats stats, InstanceProperties instance) {
        var placeholder = instance.resolve(PLACEHOLDER_IMAGE_PATH).toString();
        return new AccountResponse(
                actor.id().toString(),
                actor.username(),
                acct,
                actor.username(),
                false,
                false,
                false,
                false,
                actor.createdAt().toString(),
                "",
                actor.uri().toString(),
                actor.uri().toString(),
                placeholder,
                placeholder,
                placeholder,
                placeholder,
                0,
                0,
                stats.statusesCount(),
                stats.lastStatusAt() == null
                        ? null
                        : LocalDate.ofInstant(stats.lastStatusAt(), ZoneOffset.UTC).toString(),
                List.of(),
                List.of());
    }
}
