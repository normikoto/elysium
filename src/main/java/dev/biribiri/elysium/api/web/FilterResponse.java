package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.PostFilterMatch;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Mastodon's Filter entity.
 *
 * @param keywords left out inside a FilterResult, as on Mastodon
 * @param statuses left out inside a FilterResult, as on Mastodon
 */
@Schema(name = "Filter")
record FilterResponse(
        String id,
        String title,
        @Schema(allowableValues = {"home", "notifications", "public", "thread", "account"}) List<String> context,
        @JsonProperty("expires_at") @Schema(nullable = true, description = "When it stops applying, or null if it doesn't.")
        String expiresAt,
        @JsonProperty("filter_action") @Schema(allowableValues = {"warn", "hide", "blur"}) String filterAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<KeywordResponse> keywords,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<FilteredStatusResponse> statuses) {

    static FilterResponse from(PostFilter filter) {
        return of(filter, filter.keywords().stream().map(KeywordResponse::from).toList(),
                filter.statuses().stream().map(FilteredStatusResponse::from).toList());
    }

    private static FilterResponse of(PostFilter filter, List<KeywordResponse> keywords,
                                     List<FilteredStatusResponse> statuses) {
        return new FilterResponse(filter.id().toString(), filter.title(), contexts(filter),
                filter.expiresAt() == null ? null : ApiTimestamps.format(filter.expiresAt()), filter.action().value(),
                keywords, statuses);
    }

    static List<String> contexts(PostFilter filter) {
        return filter.contexts().stream().map(PostFilter.Context::value).sorted().toList();
    }

    @Schema(name = "FilterKeyword")
    record KeywordResponse(String id, String keyword, @JsonProperty("whole_word") boolean wholeWord) {

        static KeywordResponse from(PostFilter.Keyword keyword) {
            return new KeywordResponse(keyword.id().toString(), keyword.keyword(), keyword.wholeWord());
        }
    }

    @Schema(name = "FilterStatus")
    record FilteredStatusResponse(String id, @JsonProperty("status_id") String statusId) {

        static FilteredStatusResponse from(PostFilter.FilteredStatus status) {
            return new FilteredStatusResponse(status.id().toString(), status.statusId().toString());
        }
    }

    /**
     * Mastodon's FilterResult, which says why a status matched a filter.
     */
    @Schema(name = "FilterResult")
    record ResultResponse(
            FilterResponse filter,
            @JsonProperty("keyword_matches") @Schema(nullable = true, description = """
                    The text that the filter's keywords matched, or null if none did.""")
            List<String> keywordMatches,
            @JsonProperty("status_matches") @Schema(nullable = true, description = """
                    The IDs of the filter's statuses that matched, or null if none did.""")
            List<String> statusMatches) {

        static ResultResponse from(PostFilterMatch match) {
            return new ResultResponse(of(match.filter(), null, null),
                    match.keywordMatches().isEmpty() ? null : match.keywordMatches(),
                    match.statusMatches().isEmpty() ? null : match.statusMatches().stream().map(UUID::toString).toList());
        }
    }

    /**
     * Mastodon's V1_Filter, which is one keyword of a filter.
     */
    @Schema(name = "V1_Filter")
    record V1Response(
            @Schema(description = "The keyword's ID.") String id,
            String phrase,
            @Schema(allowableValues = {"home", "notifications", "public", "thread", "account"}) List<String> context,
            @JsonProperty("expires_at") @Schema(nullable = true) String expiresAt,
            @Schema(description = "Whether the filter's action is `hide`.") boolean irreversible,
            @JsonProperty("whole_word") boolean wholeWord) {

        static V1Response from(PostFilter filter, PostFilter.Keyword keyword) {
            return new V1Response(keyword.id().toString(), keyword.keyword(), contexts(filter),
                    filter.expiresAt() == null ? null : ApiTimestamps.format(filter.expiresAt()),
                    filter.action() == PostFilter.Action.HIDE, keyword.wholeWord());
        }
    }
}
