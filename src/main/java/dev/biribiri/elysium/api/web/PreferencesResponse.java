package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.QuotePolicy;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mastodon's Preferences entity. The settings other than the quote policy don't exist yet, so they hold what
 * {@link CredentialAccountResponse}'s {@code source} reports, or Mastodon's defaults for those it doesn't have, so that
 * the two never disagree.
 */
@Schema(name = "Preferences", description = """
        Mastodon's Preferences entity. Only `posting:default:quote_policy` can be changed, through \
        `source[quote_policy]`. The other settings don't exist yet, so they have the values a new account would have \
        on Mastodon.""")
record PreferencesResponse(
        @Schema(allowableValues = {"public", "unlisted", "private", "direct"})
        @JsonProperty("posting:default:visibility") String visibility,
        @JsonProperty("posting:default:sensitive") boolean sensitive,
        @Schema(description = "Always null, for no default.", nullable = true)
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonProperty("posting:default:language") String language,
        @Schema(allowableValues = {"public", "followers", "nobody"})
        @JsonProperty("posting:default:quote_policy") String quotePolicy,
        @Schema(allowableValues = {"default", "show_all", "hide_all"})
        @JsonProperty("reading:expand:media") String expandMedia,
        @JsonProperty("reading:expand:spoilers") boolean expandSpoilers,
        @JsonProperty("reading:autoplay:gifs") boolean autoplayGifs) {

    static PreferencesResponse from(QuotePolicy quotePolicy) {
        return new PreferencesResponse("public", false, null, quotePolicy.value(), "default", false, false);
    }
}
