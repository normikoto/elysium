package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param moved           the account this one moved to, without its own {@code moved}, or null
 */
@Schema(name = "Account", description = """
        Mastodon's Account entity. An account on another server shows the profile from its actor document, as of when \
        it was last fetched. Its avatar and header are `/media_proxy/` URLs that download them until they're kept here, \
        and links to its server if they can't be, or its media is rejected. An account with no name shows its username as `display_name`. \
        `discoverable` is always false. `pleroma` has Pleroma's `background_image`, which only a local account can \
        have.""")
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
        @Schema(description = """
                Whether the account lets anyone's search find its public statuses. For an account on another server, \
                it's what its server last said.""")
        boolean indexable,
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
        @JsonProperty("hide_collections") Boolean hideCollections,
        @Schema(description = """
                The account this one has moved to, which is left out if it hasn't moved, or that account is suspended, \
                deleted, or on a blocked server. That account's own `moved` is always left out. A local account that's \
                moved keeps working.""")
        @JsonInclude(JsonInclude.Include.NON_NULL) AccountResponse moved,
        Pleroma pleroma
) {

    private static final String PLACEHOLDER_IMAGE_PATH = "/images/missing.png";

    /**
     * Pleroma's extensions.
     *
     * @param backgroundImage null if there isn't one, which is always so for an account on another server, since
     *                        backgrounds don't federate
     */
    @Schema(name = "AccountPleroma", description = "Pleroma's extensions.")
    record Pleroma(
            @Schema(description = """
                    The profile's background, or null if it has none. Only a local account can have one, since \
                    backgrounds aren't sent to other servers.""")
            @JsonProperty("background_image") String backgroundImage) {
    }

    /**
     * Mastodon's Field entity.
     *
     * @param value      HTML, with any link marked {@code rel="me"} for a local account
     * @param verifiedAt when the page the value links to was found to link back to the profile, or null
     */
    @Schema(name = "Field")
    record Field(
            String name,
            String value,
            @Schema(description = "When the linked page was found to link back to the account with `rel=\"me\"`. "
                    + "Remote fields are checked after the actor document is stored and can be null until then.")
            @JsonProperty("verified_at") String verifiedAt) {

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
                profile.indexable(),
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
                hideCollections,
                null,
                new Pleroma(profile.background() == null ? null : profile.background().url(instance, false).toString()));
    }

    AccountResponse withMoved(AccountResponse moved) {
        return new AccountResponse(id, username, acct, displayName, locked, bot, group, discoverable, indexable, createdAt,
                note, url, uri, avatar, avatarStatic, header, headerStatic, followersCount, followingCount, statusesCount,
                lastStatusAt, emojis, fields, hideCollections, moved, pleroma);
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
