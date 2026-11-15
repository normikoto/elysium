package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PostFilterChange;
import dev.biribiri.elysium.content.PostFilters;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mastodon's v2 filters, which hold keywords and statuses.
 */
@RestController
@RequestMapping(FiltersController.PATH)
@Tag(name = "Filters", description = """
        Hiding or warning about statuses that have keywords, or are particular statuses. A status's `filtered` lists \
        the filters it matches.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/"))
public class FiltersController {

    static final String PATH = "/api/v2/filters";

    /**
     * Room for a filter with as many keywords as it can have, in which a character can take up 12 bytes of JSON.
     */
    private static final long MAX_BODY_BYTES =
            (PostFilters.MAX_TITLE_LENGTH + PostFilters.MAX_KEYWORDS_PER_FILTER * (PostFilters.MAX_KEYWORD_LENGTH + 64L))
                    * 12 + 16 * 1024;

    static final String HIDING = """
            A filter whose action is `hide` also leaves matching statuses out of what's listed in its contexts: the \
            home timeline for `home`, notifications about them for `notifications`, the public and hashtag timelines \
            for `public`, a status's context for `thread`, an account's statuses for `account`, and the streams of \
            those timelines. Fetching the status itself still gives it. Keywords are matched without regard to case, \
            in a status's content warning, text, poll options, and media descriptions.""";

    static final String LIMITS = "A user can have up to " + PostFilters.MAX_FILTERS + " filters, each with up to "
            + PostFilters.MAX_KEYWORDS_PER_FILTER + " keywords and " + PostFilters.MAX_STATUSES_PER_FILTER
            + " statuses. A title can be up to " + PostFilters.MAX_TITLE_LENGTH + " characters, and a keyword up to "
            + PostFilters.MAX_KEYWORD_LENGTH + ". Mastodon has no limit on how many there are.";

    private final PostFilters filters;
    private final JsonBodies json;

    FiltersController(PostFilters filters, JsonBodies json) {
        this.filters = filters;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "listFilters",
            summary = "View all filters",
            description = "Includes expired filters, oldest first.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filters."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<FilterResponse> list(@AuthenticationPrincipal TokenPrincipal token) {
        return filters.list(viewer(token)).stream().map(FilterResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "getFilter",
            summary = "View a filter",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public FilterResponse get(@AuthenticationPrincipal TokenPrincipal token,
                              @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(filterId -> filters.find(viewer, filterId)).map(FilterResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "createFilter",
            summary = "Create a filter",
            description = """
                    Answers 200 rather than 201, as Mastodon does. A new keyword's `whole_word` defaults to true, and a \
                    keyword left blank is ignored, as on Mastodon.

                    """ + HIDING + "\n\nDifferences from Mastodon: " + LIMITS + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new filter."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The title or context is missing, a value is refused, the user has as many filters as they \
                            can, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FilterBody.class)))
    public FilterResponse create(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = viewer(token);
        return FilterResponse.from(filters.create(viewer, change(json.readAny(request, MAX_BODY_BYTES))));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "updateFilter",
            summary = "Update a filter",
            description = """
                    What's left out stays as it is, and an empty `expires_in` makes the filter never expire. A keyword \
                    with an `id` is changed, or removed if `_destroy` is true, and one without is added. All the \
                    changes are made, or none.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter, as changed."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the ID isn't a UUID, or a keyword's `id` isn't one of the filter's."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            A value is refused, the filter would have too many keywords, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FilterChangeBody.class)))
    public FilterResponse update(@AuthenticationPrincipal TokenPrincipal token,
                                 @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                 HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var filterId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var change = change(json.readAny(request, MAX_BODY_BYTES));
        return filters.update(viewer, filterId, change).map(FilterResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "deleteFilter",
            summary = "Delete a filter",
            description = "Removes its keywords and statuses too.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> delete(@AuthenticationPrincipal TokenPrincipal token,
                                      @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        if (!RequestParameters.uuid(id).map(filterId -> filters.delete(viewer, filterId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @GetMapping("/{id}/keywords")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "listFilterKeywords",
            summary = "View keywords added to a filter",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#keywords-get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter's keywords, oldest first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<FilterResponse.KeywordResponse> keywords(@AuthenticationPrincipal TokenPrincipal token,
                                                         @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(filterId -> filters.keywords(viewer, filterId))
                .map(found -> found.stream().map(FilterResponse.KeywordResponse::from).toList())
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/keywords")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "addFilterKeyword",
            summary = "Add a keyword to a filter",
            description = """
                    `whole_word` defaults to true, as on Mastodon. Answers 200 rather than 201, as Mastodon does.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#keywords-create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new keyword."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The keyword is blank or too long, the filter has as many keywords as it can, or the token \
                            has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = KeywordBody.class)))
    public FilterResponse.KeywordResponse addKeyword(@AuthenticationPrincipal TokenPrincipal token,
                                                     @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                                     HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var filterId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        return filters.addKeyword(viewer, filterId, keyword(parameters, true),
                        FilterParameters.flag(parameters, "whole_word"))
                .map(FilterResponse.KeywordResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/keywords/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "getFilterKeyword",
            summary = "View a single keyword",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#keywords-get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The keyword."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public FilterResponse.KeywordResponse keyword(@AuthenticationPrincipal TokenPrincipal token,
                                                  @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(keywordId -> filters.keyword(viewer, keywordId))
                .map(FilterResponse.KeywordResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @PutMapping("/keywords/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "updateFilterKeyword",
            summary = "Edit a keyword within a filter",
            description = "What's left out stays as it is.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#keywords-update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The keyword, as changed."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The keyword is blank or too long, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = KeywordBody.class)))
    public FilterResponse.KeywordResponse updateKeyword(@AuthenticationPrincipal TokenPrincipal token,
                                                        @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                                        HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var keywordId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        return filters.updateKeyword(viewer, keywordId, keyword(parameters, false),
                        FilterParameters.flag(parameters, "whole_word"))
                .map(FilterResponse.KeywordResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/keywords/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "deleteFilterKeyword",
            summary = "Remove keywords from a filter",
            description = "The filter stays, even with no keywords left, as on Mastodon.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#keywords-delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> deleteKeyword(@AuthenticationPrincipal TokenPrincipal token,
                                             @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        if (!RequestParameters.uuid(id).map(keywordId -> filters.deleteKeyword(viewer, keywordId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @GetMapping("/{id}/statuses")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "listFilterStatuses",
            summary = "View all status filters",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#statuses-get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter's statuses, oldest first."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<FilterResponse.FilteredStatusResponse> statuses(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(filterId -> filters.statuses(viewer, filterId))
                .map(found -> found.stream().map(FilterResponse.FilteredStatusResponse::from).toList())
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/statuses")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "addFilterStatus",
            summary = "Add a status to a filter",
            description = """
                    The filter also matches boosts of the status. A status that's deleted is taken out of the filter. \
                    Answers 200 rather than 201, as Mastodon does.

                    Differences from Mastodon: the status has to be one the user can see, or the answer is a 404.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#statuses-add"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new status filter."),
                    @ApiResponse(responseCode = "404", description = """
                            The filter or status isn't found, or one of their IDs isn't a UUID."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The filter already has the status, or has as many as it can, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StatusBody.class)))
    public FilterResponse.FilteredStatusResponse addStatus(@AuthenticationPrincipal TokenPrincipal token,
                                                           @Parameter(description = ApiDocs.ID) @PathVariable String id,
                                                           HttpServletRequest request) throws IOException {
        var viewer = viewer(token);
        var filterId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var statusId = RequestParameters.string(json.readAny(request, MAX_BODY_BYTES), "status_id");
        if (statusId == null) throw new InvalidRequestException("Validation failed: Status can't be blank");
        return RequestParameters.uuid(statusId).flatMap(status -> filters.addStatus(viewer, filterId, status))
                .map(FilterResponse.FilteredStatusResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @GetMapping("/statuses/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "getFilterStatus",
            summary = "View a single status filter",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#statuses-get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status filter."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public FilterResponse.FilteredStatusResponse status(@AuthenticationPrincipal TokenPrincipal token,
                                                        @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(entryId -> filters.status(viewer, entryId))
                .map(FilterResponse.FilteredStatusResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/statuses/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "deleteFilterStatus",
            summary = "Remove a status from a filter",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#statuses-remove"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> deleteStatus(@AuthenticationPrincipal TokenPrincipal token,
                                            @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        if (!RequestParameters.uuid(id).map(entryId -> filters.deleteStatus(viewer, entryId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    static UUID viewer(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    private static PostFilterChange change(Map<String, ?> parameters) {
        return new PostFilterChange(RequestParameters.string(parameters, "title"), FilterParameters.contexts(parameters),
                FilterParameters.action(parameters), FilterParameters.expiry(parameters),
                FilterParameters.keywords(parameters));
    }

    /**
     * @param required whether a missing keyword is refused, rather than kept
     */
    private static String keyword(Map<String, ?> parameters, boolean required) {
        var keyword = RequestParameters.string(parameters, "keyword");
        if (keyword == null && required) throw new InvalidRequestException("Validation failed: Keyword can't be blank");
        return keyword;
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewFilter")
    private record FilterBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The filter's name.") String title,
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    Where it applies. Sent as `context[]` in a form."""),
                    schema = @Schema(allowableValues = {"home", "notifications", "public", "thread", "account"}))
            List<String> context,
            @Schema(allowableValues = {"warn", "hide", "blur"}, defaultValue = "warn") String filter_action,
            @Schema(description = "How many seconds until it expires. Left out or empty, it never does.")
            Integer expires_in,
            @Schema(description = """
                    Its keywords. A form sends them as `keywords_attributes[][keyword]` and \
                    `keywords_attributes[][whole_word]`, or numbered, as `keywords_attributes[0][keyword]`.""")
            List<KeywordAttributes> keywords_attributes) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "FilterChange")
    private record FilterChangeBody(
            String title,
            @ArraySchema(schema = @Schema(allowableValues = {"home", "notifications", "public", "thread", "account"}))
            List<String> context,
            @Schema(allowableValues = {"warn", "hide", "blur"}) String filter_action,
            @Schema(description = "How many seconds until it expires. Empty, it never does.") Integer expires_in,
            @Schema(description = """
                    Keywords to add, or with `id`, to change or remove.""")
            List<KeywordAttributes> keywords_attributes) {
    }

    @Schema(name = "FilterKeywordAttributes")
    private record KeywordAttributes(
            @Schema(description = "A keyword to change or remove. Left out, the keyword is added.") String id,
            String keyword,
            @Schema(description = "Whether it only matches whole words. True for a new keyword if left out.")
            Boolean whole_word,
            @Schema(description = "Whether to remove the keyword with this `id`.") Boolean _destroy) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewFilterKeyword")
    private record KeywordBody(
            @Schema(description = "Up to " + PostFilters.MAX_KEYWORD_LENGTH + " characters.") String keyword,
            @Schema(description = "Whether it only matches whole words.") Boolean whole_word) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewFilterStatus")
    private record StatusBody(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status_id) {
    }
}
