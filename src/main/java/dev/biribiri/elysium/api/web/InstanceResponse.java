package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Mastodon's V1 Instance entity, plus Pleroma's {@code max_toot_chars}. Clients read the post length limit from one or
 * the other, so both are included. Features that don't exist yet report zero limits, and fields with no setting yet are
 * empty, because some clients fail to parse a response with fields missing.
 */
record InstanceResponse(
        String uri,
        String title,
        @JsonProperty("short_description") String shortDescription,
        String description,
        String email,
        String version,
        Urls urls,
        Stats stats,
        String thumbnail,
        List<String> languages,
        boolean registrations,
        @JsonProperty("approval_required") boolean approvalRequired,
        @JsonProperty("invites_enabled") boolean invitesEnabled,
        @JsonProperty("max_toot_chars") int maxTootChars,
        Configuration configuration,
        @JsonProperty("contact_account") Object contactAccount,
        List<Object> rules
) {

    record Urls(@JsonProperty("streaming_api") String streamingApi) {
    }

    record Stats(
            @JsonProperty("user_count") long userCount,
            @JsonProperty("status_count") long statusCount,
            @JsonProperty("domain_count") long domainCount
    ) {
    }

    record Configuration(Accounts accounts, Statuses statuses,
                         @JsonProperty("media_attachments") MediaAttachments mediaAttachments, Polls polls) {
    }

    record Accounts(@JsonProperty("max_featured_tags") int maxFeaturedTags) {
    }

    record Statuses(
            @JsonProperty("max_characters") int maxCharacters,
            @JsonProperty("max_media_attachments") int maxMediaAttachments,
            @JsonProperty("characters_reserved_per_url") int charactersReservedPerUrl
    ) {
    }

    record MediaAttachments(
            @JsonProperty("supported_mime_types") List<String> supportedMimeTypes,
            @JsonProperty("image_size_limit") int imageSizeLimit,
            @JsonProperty("image_matrix_limit") int imageMatrixLimit,
            @JsonProperty("video_size_limit") int videoSizeLimit,
            @JsonProperty("video_frame_rate_limit") int videoFrameRateLimit,
            @JsonProperty("video_matrix_limit") int videoMatrixLimit
    ) {
    }

    record Polls(
            @JsonProperty("max_options") int maxOptions,
            @JsonProperty("max_characters_per_option") int maxCharactersPerOption,
            @JsonProperty("min_expiration") int minExpiration,
            @JsonProperty("max_expiration") int maxExpiration
    ) {
    }
}
