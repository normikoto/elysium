package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.Trends;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

@RestController
@RequestMapping(TrendsController.PATH)
@PreAuthorize("isAnonymous() or isAuthenticated()")
@Tag(name = "Trends", description = "Tags, statuses, and links with recent activity.",
        externalDocs = @ExternalDocumentation(url = TrendsController.DOCS))
@ApiResponse(responseCode = "422", description = "The limit or offset is not a number.")
public class TrendsController {

    static final String PATH = "/api/v1/trends";
    static final String DOCS = ApiDocs.MASTODON_DOCS + "trends/";

    private static final String TAGS = "/tags";
    private static final String STATUSES = "/statuses";
    private static final String LINKS = "/links";
    private static final String OFFSET = "Number of ranked results to skip. Negative values count as zero.";
    private static final String PAGE_LINKS = "Offset links to the next page when the query returns a full page and to "
            + "the previous page when offset is positive.";
    private static final String PUBLIC = """
            Available with any scope or without a token. Ranking is language-independent and uses activity stored \
            on this server. The list is empty if the operator has disabled trends.""";

    private final Trends trends;
    private final TagResponses tags;
    private final StatusResponses statuses;
    private final InstanceProperties instance;

    TrendsController(Trends trends, TagResponses tags, StatusResponses statuses, InstanceProperties instance) {
        this.trends = trends;
        this.tags = tags;
        this.statuses = statuses;
        this.instance = instance;
    }

    @GetMapping(TAGS)
    @Operation(operationId = "getTrendingTags", summary = "View trending tags",
            description = """
                    Ranks hashtags used by at least two distinct accounts in the past 48 hours. Elysium identifies \
                    tags by their normalized names. History counts eligible public posts over seven UTC days.

                    """ + PUBLIC,
            externalDocs = @ExternalDocumentation(url = DOCS + "#view-trending-tags"),
            responses = @ApiResponse(responseCode = "200", description = "The ranked tags.",
                    headers = @Header(name = HttpHeaders.LINK, description = PAGE_LINKS)))
    public ResponseEntity<List<TagResponse>> tags(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = "Number of tags to return, clamped to 1 through 20.",
                    schema = @Schema(type = "integer", defaultValue = "" + PageLimits.TREND_TAGS_AND_LINKS))
            @RequestParam(required = false) String limit,
            @Parameter(description = OFFSET, schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(required = false) String offset) {
        return respond(TAGS, token, limit, offset, PageLimits.TREND_TAGS_AND_LINKS, PageLimits.MAX_TREND_TAGS_AND_LINKS,
                trends::tags, tags::trending);
    }

    @GetMapping(STATUSES)
    @Operation(operationId = "getTrendingStatuses", summary = "View trending statuses",
            description = """
                    Ranks eligible public statuses from discoverable accounts published in the past seven days. \
                    At least two distinct other accounts must have favourited or publicly boosted the status in \
                    the past 48 hours. A user's public post filters can remove results from a page.

                    """ + PUBLIC,
            externalDocs = @ExternalDocumentation(url = DOCS + "#view-trending-statuses"),
            responses = @ApiResponse(responseCode = "200", description = "The ranked statuses.",
                    headers = @Header(name = HttpHeaders.LINK, description = PAGE_LINKS)))
    public ResponseEntity<List<StatusResponse>> statuses(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = "Number of statuses to return, clamped to 1 through 40.",
                    schema = @Schema(type = "integer", defaultValue = "" + PageLimits.STATUSES))
            @RequestParam(required = false) String limit,
            @Parameter(description = OFFSET, schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(required = false) String offset) {
        return respond(STATUSES, token, limit, offset, PageLimits.STATUSES, PageLimits.MAX_STATUSES, trends::statuses,
                (found, viewer) -> statuses.list(found, viewer, Optional.of(PostFilter.Context.PUBLIC)));
    }

    @GetMapping(LINKS)
    @Operation(operationId = "getTrendingLinks", summary = "View trending links",
            description = """
                    Ranks links shared by at least two distinct accounts in the past 48 hours. History counts \
                    eligible public posts over seven UTC days. Cards have the URL and host, with empty page metadata.

                    """ + PUBLIC,
            externalDocs = @ExternalDocumentation(url = DOCS + "#view-trending-links"),
            responses = @ApiResponse(responseCode = "200", description = "The ranked links.",
                    headers = @Header(name = HttpHeaders.LINK, description = PAGE_LINKS)))
    public ResponseEntity<List<TrendLinkResponse>> links(
            @AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = "Number of links to return, clamped to 1 through 20.",
                    schema = @Schema(type = "integer", defaultValue = "" + PageLimits.TREND_TAGS_AND_LINKS))
            @RequestParam(required = false) String limit,
            @Parameter(description = OFFSET, schema = @Schema(type = "integer", defaultValue = "0"))
            @RequestParam(required = false) String offset) {
        return respond(LINKS, token, limit, offset, PageLimits.TREND_TAGS_AND_LINKS, PageLimits.MAX_TREND_TAGS_AND_LINKS,
                trends::links, (found, viewer) -> found.stream()
                        .map(link -> TrendLinkResponse.from(link, viewer.isPresent())).toList());
    }

    /**
     * Pages are linked by how many were found, before they become responses, since post filters can remove
     * statuses from a full page.
     */
    private <T, R> ResponseEntity<List<R>> respond(String path, TokenPrincipal token, String limit, String offset,
                                                   int defaultLimit, int maxLimit, Query<T> query,
                                                   BiFunction<List<T>, Optional<UUID>, List<R>> toResponses) {
        var pageLimit = Math.clamp(RequestParameters.number("limit", limit, defaultLimit), 1, maxLimit);
        var pageOffset = Math.max(0, RequestParameters.number("offset", offset, 0));
        if (!trends.enabled()) {
            return ResponseEntity.ok(List.of());
        }
        var viewer = RequestParameters.viewer(token);
        var found = query.find(viewer, pageLimit, pageOffset);
        var response = ResponseEntity.ok();
        PageLinks.addOffsetTo(response, instance, PATH + path, pageLimit, pageOffset, found.size());
        return response.body(toResponses.apply(found, viewer));
    }

    private interface Query<T> {
        List<T> find(Optional<UUID> viewer, int limit, int offset);
    }
}
