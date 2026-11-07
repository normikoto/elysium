package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
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
     * No status has media yet, so {@code only_media} has nothing to return. Mastodon lets {@code local} win when both it and
     * {@code remote} are given.
     */
    @GetMapping("/public")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getPublicTimeline",
            summary = "View public statuses",
            description = """
                    Differences from Mastodon: statuses from other servers are only stored when they're sent here, \
                    which is when a local account follows their author or is addressed. Statuses can't have media, so \
                    `only_media` returns an empty list.

                    Only public statuses are listed, and not ones from suspended accounts.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/#public"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "local", description = "Only list statuses from this server. " + ApiDocs.FLAG),
                    @Parameter(name = "remote", description = "Only list statuses from other servers. Ignored if "
                            + "`local` is true. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Returns an empty list if true. " + ApiDocs.FLAG)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first.")
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
        var origin = RequestParameters.flag(local) ? StatusOrigin.LOCAL
                : RequestParameters.flag(remote) ? StatusOrigin.REMOTE
                : StatusOrigin.ANY;
        List<Status> found = RequestParameters.flag(onlyMedia) ? List.of() : timelines.publicTimeline(origin, page);

        var filters = new LinkedHashMap<String, String>();
        switch (origin) {
            case LOCAL -> filters.put("local", "true");
            case REMOTE -> filters.put("remote", "true");
            case ANY -> {
            }
        }
        return responses.page(PUBLIC_PATH, filters, page, found);
    }
}
