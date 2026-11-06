package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.graph.FollowCounts;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Account entity. Profiles don't exist yet, so those fields hold the values a new, empty account would have.
 * Clients need real image URLs, so those point to a bundled placeholder. Build these with {@link AccountResponses}.
 *
 * @param followersCount for a remote account, only its followers on this server
 * @param followingCount for a remote account, only the accounts on this server it follows
 * @param hideCollections whether a local account hides its followers and following, or null for a remote account, whose
 *                        server decides
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
        @JsonProperty("followers_count") long followersCount,
        @JsonProperty("following_count") long followingCount,
        @JsonProperty("statuses_count") long statusesCount,
        @JsonProperty("last_status_at") String lastStatusAt,
        List<Object> emojis,
        List<Object> fields,
        @JsonProperty("hide_collections") Boolean hideCollections
) {

    private static final String PLACEHOLDER_IMAGE_PATH = "/images/missing.png";

    /**
     * @param acct the username for a local account, and the username and domain for a remote one
     */
    static AccountResponse from(
            Actor actor,
            String acct,
            AccountStats stats,
            FollowCounts follows,
            Boolean hideCollections,
            InstanceProperties instance) {
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
                follows.followers(),
                follows.following(),
                stats.statusesCount(),
                stats.lastStatusAt() == null
                        ? null
                        : LocalDate.ofInstant(stats.lastStatusAt(), ZoneOffset.UTC).toString(),
                List.of(),
                List.of(),
                hideCollections);
    }
}
