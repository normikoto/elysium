package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileImage;
import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.graph.FollowCounts;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Account entity. Profiles of remote accounts aren't stored yet, so theirs show the username and no more.
 * Clients need real image URLs, so a missing image is a bundled placeholder. Build these with {@link AccountResponses}.
 *
 * @param followersCount for a remote account, only its followers on this server
 * @param followingCount for a remote account, only the accounts on this server it follows
 * @param hideCollections whether a local account hides its followers and following, or null for a remote account, whose
 *                        server decides
 */
@Schema(name = "Account", description = """
        Mastodon's Account entity. Profiles of accounts on other servers aren't stored yet, so for those `display_name` \
        is the username, `note` and `fields` are empty, and the images are a placeholder. `emojis` is always empty, and \
        `locked`, `bot`, `group`, and `discoverable` are false.""")
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
        @Schema(description = "For an account on another server, only its followers on this server.")
        @JsonProperty("followers_count") long followersCount,
        @Schema(description = "For an account on another server, only the accounts on this server that it follows.")
        @JsonProperty("following_count") long followingCount,
        @JsonProperty("statuses_count") long statusesCount,
        @JsonProperty("last_status_at") String lastStatusAt,
        List<Object> emojis,
        List<Field> fields,
        @Schema(description = """
                Whether the account hides who's in its followers and following, or null for an account on another \
                server.""")
        @JsonProperty("hide_collections") Boolean hideCollections
) {

    private static final String PLACEHOLDER_IMAGE_PATH = "/images/missing.png";

    /**
     * Mastodon's Field entity.
     *
     * @param value      HTML, with any link marked {@code rel="me"}
     * @param verifiedAt when the page the value links to was found to link back to the profile, or null
     */
    @Schema(name = "Field")
    record Field(String name, String value, @JsonProperty("verified_at") String verifiedAt) {
    }

    /**
     * @param acct    the username for a local account, and the username and domain for a remote one
     * @param profile null for a remote account, whose profile isn't stored
     */
    static AccountResponse from(
            Actor actor,
            String acct,
            Profile profile,
            AccountStats stats,
            FollowCounts follows,
            Boolean hideCollections,
            InstanceProperties instance) {
        var placeholder = instance.resolve(PLACEHOLDER_IMAGE_PATH).toString();
        var shown = profile == null ? Profile.EMPTY : profile;
        return new AccountResponse(
                actor.id().toString(),
                actor.username(),
                acct,
                // Mastodon sends an empty display name as it is, and clients show the username instead.
                profile == null ? actor.username() : profile.displayName(),
                false,
                false,
                false,
                false,
                actor.createdAt().toString(),
                shown.noteHtml(),
                actor.uri().toString(),
                actor.uri().toString(),
                url(shown.avatar(), false, placeholder, instance),
                url(shown.avatar(), true, placeholder, instance),
                url(shown.header(), false, placeholder, instance),
                url(shown.header(), true, placeholder, instance),
                follows.followers(),
                follows.following(),
                stats.statusesCount(),
                stats.lastStatusAt() == null
                        ? null
                        : LocalDate.ofInstant(stats.lastStatusAt(), ZoneOffset.UTC).toString(),
                List.of(),
                shown.fields().stream()
                        .map(field -> new Field(
                                field.name(),
                                field.valueHtml(),
                                field.verifiedAt() == null ? null : field.verifiedAt().toString()))
                        .toList(),
                hideCollections);
    }

    private static String url(ProfileImage image, boolean still, String placeholder, InstanceProperties instance) {
        if (image == null) {
            return placeholder;
        }
        return instance.resolve(still ? image.staticPath() : image.path()).toString();
    }
}
