package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.timeline.TimelineService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;

@RestController
@RequestMapping("/api/v1/timelines")
@Tag(name = "Timelines", description = "Reading feeds of statuses.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/"))
public class TimelinesController {

    private static final String PUBLIC_PATH = "/api/v1/timelines/public";

    private final TimelineService timelines;
    private final StatusResponses responses;

    TimelinesController(TimelineService timelines, StatusResponses responses) {
        this.timelines = timelines;
        this.responses = responses;
    }

    /**
     * Every status is local and none have media yet, so {@code local} has no effect, and {@code remote} and
     * {@code only_media} have nothing to return.
     */
    @GetMapping("/public")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getPublicTimeline",
            summary = "View public statuses",
            description = """
                    Differences from Mastodon: statuses from other servers aren't stored, so every status is from this \
                    server. `local` has no effect, and `remote` returns an empty list. Statuses can't have media, so \
                    `only_media` returns an empty list.

                    Only public statuses are listed, and not ones from suspended accounts.""" + " " + ApiDocs.PAGE,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/#public"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "max_id", description = ApiDocs.MAX_ID),
                    @Parameter(name = "since_id", description = ApiDocs.SINCE_ID),
                    @Parameter(name = "min_id", description = ApiDocs.MIN_ID),
                    @Parameter(name = "local", description = "Has no effect. " + ApiDocs.FLAG),
                    @Parameter(name = "remote", description = "Returns an empty list if true. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Returns an empty list if true. " + ApiDocs.FLAG)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first."),
                    @ApiResponse(responseCode = "422", description = "A cursor or `limit` is invalid.")
            })
    public ResponseEntity<List<StatusResponse>> publicTimeline(
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(required = false) String local,
            @RequestParam(required = false) String remote,
            @RequestParam(name = "only_media", required = false) String onlyMedia) {
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var nothingToReturn = RequestParameters.flag(remote) || RequestParameters.flag(onlyMedia);
        List<Status> found = nothingToReturn ? List.of() : timelines.publicTimeline(page);

        var filters = new LinkedHashMap<String, String>();
        if (RequestParameters.flag(local)) {
            filters.put("local", "true");
        }
        return responses.page(PUBLIC_PATH, filters, page, found);
    }
}
