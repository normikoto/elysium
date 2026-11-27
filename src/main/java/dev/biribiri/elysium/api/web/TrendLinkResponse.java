package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Trends;
import io.swagger.v3.oas.annotations.media.Schema;

import java.net.URI;
import java.util.List;

@Schema(name = "TrendLink", description = "A shared link with seven days of use. The title is the URL and the provider "
        + "name is its host. Page metadata is empty because Elysium does not fetch the page.")
record TrendLinkResponse(
        String url,
        String title,
        String description,
        String language,
        String type,
        @JsonProperty("author_name") String authorName,
        @JsonProperty("author_url") String authorUrl,
        @JsonProperty("provider_name") String providerName,
        @JsonProperty("provider_url") String providerUrl,
        String html,
        int width,
        int height,
        String image,
        @JsonProperty("image_description") String imageDescription,
        @JsonProperty("embed_url") String embedUrl,
        String blurhash,
        @JsonProperty("published_at") String publishedAt,
        List<Author> authors,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("missing_attribution") Boolean missingAttribution,
        List<TagResponse.History> history) {

    @Schema(name = "PreviewCardAuthor")
    record Author(String name, String url, AccountResponse account) {
    }

    static TrendLinkResponse from(Trends.Sharing link, boolean hasViewer) {
        var url = link.value();
        return new TrendLinkResponse(url, url, "", null, "link", "", "", URI.create(url).getHost(), "", "", 0, 0,
                null, "", "", null, null, List.of(), hasViewer ? Boolean.FALSE : null, TagResponses.trendHistory(link));
    }
}
