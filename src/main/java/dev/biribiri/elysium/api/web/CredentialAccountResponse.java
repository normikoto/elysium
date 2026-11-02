package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;

import java.util.List;

/**
 * Mastodon's CredentialAccount entity. Profiles, follows, and posts don't exist yet, so those fields hold the values a
 * new, empty account would have. Clients need real image URLs, so those point to a bundled placeholder.
 */
record CredentialAccountResponse(
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
        @JsonProperty("statuses_count") int statusesCount,
        @JsonProperty("last_status_at") String lastStatusAt,
        List<Object> emojis,
        List<Object> fields,
        Source source
) {

    static CredentialAccountResponse from(Actor actor, String placeholderImage) {
        return new CredentialAccountResponse(
                actor.id().toString(),
                actor.username(),
                actor.username(),
                actor.username(),
                false,
                false,
                false,
                false,
                actor.createdAt().toString(),
                "",
                actor.uri().toString(),
                actor.uri().toString(),
                placeholderImage,
                placeholderImage,
                placeholderImage,
                placeholderImage,
                0,
                0,
                0,
                null,
                List.of(),
                List.of(),
                new Source("public", false, "", "", List.of(), 0));
    }

    record Source(
            String privacy,
            boolean sensitive,
            String language,
            String note,
            List<Object> fields,
            @JsonProperty("follow_requests_count") int followRequestsCount
    ) {
    }
}
