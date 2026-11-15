package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileField;
import dev.biribiri.elysium.actor.ProfileImage;
import dev.biribiri.elysium.content.AccountStats;
import dev.biribiri.elysium.graph.FollowCounts;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Account entity. Clients need real image URLs, so a missing image is a bundled placeholder. Build these with
 * {@link AccountResponses}.
 *
 * @param followersCount for a remote account, only its followers on this server
 * @param followingCount for a remote account, only the accounts on this server it follows
 * @param hideCollections whether a local account hides its followers and following, or null for a remote account, whose
 *                        server decides
 */
@Schema(name = "Account", description = """
        Mastodon's Account entity. An account on another server shows the profile from its actor document, as of when \
        it was last fetched. Its avatar and header are links to its server until they're downloaded, and stay links if \
        they can't be, or its media is rejected. An account with no name shows its username as `display_name`. \
        `discoverable` is always false.""")
record AccountResponse(
        String id,
        String username,
        String acct,
        @JsonProperty("display_name") String displayName,
        @Schema(description = "Whether the account approves its followers by hand.")
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
        List<CustomEmojiResponse> emojis,
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
     * @param value      HTML, with any link marked {@code rel="me"} for a local account
     * @param verifiedAt when the page the value links to was found to link back to the profile, or null. Fields of
     *                   accounts on other servers aren't checked, so theirs is always null.
     */
    @Schema(name = "Field")
    record Field(String name, String value, @JsonProperty("verified_at") String verifiedAt) {

        static Field of(ProfileField field, String value) {
            return new Field(
                    field.name(), value, field.verifiedAt() == null ? null : ApiTimestamps.format(field.verifiedAt()));
        }
    }

    /**
     * @param local   whether the account is on this server
     * @param acct    the username for a local account, and the username and domain for a remote one
     * @param emojis  the custom emoji the profile names
     */
    static AccountResponse from(
            Actor actor,
            boolean local,
            String acct,
            Profile profile,
            AccountStats stats,
            FollowCounts follows,
            Boolean hideCollections,
            List<CustomEmojiResponse> emojis,
            InstanceProperties instance) {
        var placeholder = instance.resolve(PLACEHOLDER_IMAGE_PATH).toString();
        var remote = profile.remote();
        return new AccountResponse(
                actor.id().toString(),
                actor.username(),
                acct,
                // Mastodon sends a local account's empty display name as it is, and clients show the username instead.
                // An account on another server without one has always been shown with its username here.
                !local && profile.displayName().isEmpty() ? actor.username() : profile.displayName(),
                profile.manuallyApprovesFollowers(),
                remote != null && remote.bot(),
                remote != null && remote.group(),
                false,
                createdAt(actor, remote),
                profile.noteHtml(instance.baseUrl()),
                remote != null && remote.url() != null ? remote.url().toString() : actor.uri().toString(),
                actor.uri().toString(),
                url(profile.avatar(), false, placeholder, instance),
                url(profile.avatar(), true, placeholder, instance),
                url(profile.header(), false, placeholder, instance),
                url(profile.header(), true, placeholder, instance),
                follows.followers(),
                follows.following(),
                stats.statusesCount(),
                stats.lastStatusAt() == null
                        ? null
                        : LocalDate.ofInstant(stats.lastStatusAt(), ZoneOffset.UTC).toString(),
                emojis,
                profile.fields().stream().map(field -> Field.of(field, field.valueHtml())).toList(),
                hideCollections);
    }

    private static String url(ProfileImage image, boolean still, String placeholder, InstanceProperties instance) {
        if (image == null) {
            return placeholder;
        }
        return image.url(instance, still).toString();
    }

    /**
     * Mastodon shows when an account on another server says it was created, at midnight, as it does for its own.
     */
    private static String createdAt(Actor actor, Profile.Remote remote) {
        if (remote == null || remote.published() == null) {
            return actor.createdAt().toString();
        }
        return LocalDate.ofInstant(remote.published(), ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
    }
}
