package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BookmarkService;
import dev.biribiri.elysium.content.StatusPage;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping(BookmarksController.PATH)
@Tag(name = "Bookmarks", description = "The statuses the user has bookmarked. Statuses are bookmarked through the "
        + "statuses API.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "bookmarks/"))
public class BookmarksController {

    static final String PATH = "/api/v1/bookmarks";

    private final BookmarkService bookmarks;
    private final StatusResponses responses;

    BookmarksController(BookmarkService bookmarks, StatusResponses responses) {
        this.bookmarks = bookmarks;
        this.responses = responses;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:bookmarks')")
    @Operation(
            operationId = "getBookmarks",
            summary = "List the statuses the user has bookmarked",
            description = """
                    Pages are by when each status was bookmarked, and the cursors are the IDs in the `Link` header, not \
                    status IDs, as on Mastodon. Statuses the user can no longer see are left out, so a page can be \
                    shorter than `limit` and still have more after it. Filters don't hide statuses here, but are given \
                    in `filtered`.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "bookmarks/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The statuses, most recent bookmark first."),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<StatusResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var found = bookmarks.page(viewer, page);
        return responses.page(PATH, page, found.bookmarkIds(), found.statuses(), Optional.of(viewer));
    }
}
