package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.text.Hashtags;
import dev.biribiri.elysium.timeline.TimelineService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/timelines")
@Tag(name = "Timelines", description = "Reading feeds of statuses.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/"))
public class TimelinesController {

    private static final String PUBLIC_PATH = "/api/v1/timelines/public";
    private static final String HOME_PATH = "/api/v1/timelines/home";
    private static final String TAG_PATH = "/api/v1/timelines/tag/";

    /**
     * How many tags each of {@code any[]}, {@code all[]} and {@code none[]} can name, as on Mastodon, which ignores the
     * rest.
     */
    private static final int TAGS_PER_MODE = 4;

    private final TimelineService timelines;
    private final StatusResponses responses;

    TimelinesController(TimelineService timelines, StatusResponses responses) {
        this.timelines = timelines;
        this.responses = responses;
    }

    @GetMapping("/home")
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getHomeTimeline",
            summary = "View statuses from followed accounts",
            description = """
                    Differences from Mastodon: the timeline is read from the statuses stored here each time, rather than \
                    from a feed kept for each user, so it never answers `206`, and a newly followed account's stored \
                    statuses show straight away, including older ones. Statuses from other servers are only those sent \
                    here since the follow was accepted, or before it if someone else here follows the author. A followed \
                    account's reply to a status that isn't stored here is left out, since who it's replying to isn't known. Every boost \
                    from a followed account is listed, even of a status that's already there. `local` has no effect.

                    Lists all of the user's own statuses, visible statuses of accounts they follow, and direct \
                    statuses from followed accounts that address the user. A followed account's reply is only listed \
                    when it's to its author, to the user, \
                    or to an account they follow.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/#home"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "local", description = "Has no effect. Mastodon has deprecated it.")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public ResponseEntity<List<StatusResponse>> homeTimeline(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(required = false) String local) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        return responses.page(HOME_PATH, Map.of(), page, timelines.homeTimeline(viewer, page), Optional.of(viewer));
    }

    @GetMapping("/public")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getPublicTimeline",
            summary = "View public statuses",
            description = """
                    Differences from Mastodon: statuses from other servers are only stored when they're sent here, \
                    which is when a local account follows their author or is addressed.

                    Only public statuses are listed, and not ones from suspended accounts. With a user token, ones \
                    from accounts the user has blocked, muted, or blocked the server of, or that block the user, are \
                    left out too.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/#public"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "local", description = "Only list statuses from this server. " + ApiDocs.FLAG),
                    @Parameter(name = "remote", description = "Only list statuses from other servers. Ignored if "
                            + "`local` is true. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Only list statuses with media. " + ApiDocs.FLAG)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first.")
            })
    public ResponseEntity<List<StatusResponse>> publicTimeline(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(required = false) String local,
            @RequestParam(required = false) String remote,
            @RequestParam(name = "only_media", required = false) String onlyMedia) {
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var origin = RequestParameters.origin(local, remote);
        List<Status> found = timelines.publicTimeline(
                origin, page, RequestParameters.flag(onlyMedia), RequestParameters.viewer(token));

        var filters = new LinkedHashMap<String, String>();
        RequestParameters.putOrigin(filters, origin);
        if (RequestParameters.flag(onlyMedia)) {
            filters.put("only_media", "true");
        }
        return responses.page(PUBLIC_PATH, filters, page, found, RequestParameters.viewer(token));
    }

    /**
     * Mastodon answers a name that isn't a tag with an empty list rather than a 404, and so does this. A name in
     * {@code all[]} that isn't a tag can't match anything, so it empties the list, while one in {@code any[]} or
     * {@code none[]} is ignored.
     */
    @GetMapping("/tag/{hashtag}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getTagTimeline",
            summary = "View public statuses with a hashtag",
            description = """
                    Differences from Mastodon: statuses from other servers are only stored when they're sent here, \
                    which is when a local account follows their author or is addressed, and their hashtags are only \
                    those in their `tag`. Tags are \
                    matched without regard to case or Unicode composition, as Mastodon's normalized names are.

                    Only public statuses are listed, including replies, and not ones from suspended accounts, or, with \
                    a user token, from accounts the user has blocked, muted, or blocked the server of, or that block \
                    the user. A `hashtag` that isn't a valid tag name gives an empty list.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "timelines/#tag"),
            parameters = {
                    @Parameter(name = "hashtag", description = "The tag's name, without its `#`."),
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT)),
                    @Parameter(name = "any[]", description = "Also list statuses with any of these tags. Only the "
                            + "first " + TAGS_PER_MODE + " count."),
                    @Parameter(name = "all[]", description = "Only list statuses that also have every one of these "
                            + "tags. Only the first " + TAGS_PER_MODE + " count."),
                    @Parameter(name = "none[]", description = "Leave out statuses with any of these tags. Only the "
                            + "first " + TAGS_PER_MODE + " count."),
                    @Parameter(name = "local", description = "Only list statuses from this server. " + ApiDocs.FLAG),
                    @Parameter(name = "remote", description = "Only list statuses from other servers. Ignored if "
                            + "`local` is true. " + ApiDocs.FLAG),
                    @Parameter(name = "only_media", description = "Only list statuses with media. " + ApiDocs.FLAG)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, newest first.")
            })
    public ResponseEntity<List<StatusResponse>> tagTimeline(
            @AuthenticationPrincipal TokenPrincipal token,
            @PathVariable String hashtag,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId,
            @RequestParam(name = "any[]", required = false) List<String> any,
            @RequestParam(name = "all[]", required = false) List<String> all,
            @RequestParam(name = "none[]", required = false) List<String> none,
            @RequestParam(required = false) String local,
            @RequestParam(required = false) String remote,
            @RequestParam(name = "only_media", required = false) String onlyMedia) {
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var origin = RequestParameters.origin(local, remote);
        var anyNames = first(any);
        var allNames = first(all);
        var noneNames = first(none);
        var filter = TagFilter.parse(hashtag, anyNames, allNames, noneNames);
        // Without a Link header, which would have to repeat a name that may not fit in a URL.
        if (filter.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<Status> found = timelines.tagTimeline(
                filter.get(), origin, page, RequestParameters.flag(onlyMedia), RequestParameters.viewer(token));

        var filters = new LinkedHashMap<String, Object>();
        for (var mode : List.of(Map.entry("any[]", anyNames), Map.entry("all[]", allNames), Map.entry("none[]", noneNames))) {
            if (!mode.getValue().isEmpty()) {
                filters.put(mode.getKey(), mode.getValue());
            }
        }
        RequestParameters.putOrigin(filters, origin);
        if (RequestParameters.flag(onlyMedia)) {
            filters.put("only_media", "true");
        }
        var path = TAG_PATH + Hashtags.parse(hashtag).orElseThrow();
        return responses.page(path, filters, page, found, RequestParameters.viewer(token));
    }

    private static List<String> first(List<String> names) {
        return names == null ? List.of() : names.stream().limit(TAGS_PER_MODE).toList();
    }
}
