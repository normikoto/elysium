package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PostFilter;
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

/**
 * Mastodon's deprecated v1 filters, which some clients still only know. Each is one keyword of a v2 filter, by the
 * keyword's ID.
 */
@RestController
@RequestMapping("/api/v1/filters")
@Tag(name = "Filters (v1)", description = """
        Mastodon's older filters, each of which is one keyword of a filter from `/api/v2/filters`. A filter with \
        several keywords is listed once for each.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#v1"))
public class V1FiltersController {

    private static final long MAX_BODY_BYTES = PostFilters.MAX_KEYWORD_LENGTH * 12L + 4 * 1024;

    private static final String IRREVERSIBLE = """
            `irreversible` makes the filter's action `hide`, and a filter whose action is `blur` reads as not \
            irreversible.""";

    private final PostFilters filters;
    private final JsonBodies json;

    V1FiltersController(PostFilters filters, JsonBodies json) {
        this.filters = filters;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "listV1Filters",
            summary = "View all filters (v1)",
            description = """
                    One for each keyword of the user's filters, including expired ones. Filters with no keywords, \
                    such as those that only hold statuses, aren't listed.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#get-v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filters."),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public List<FilterResponse.V1Response> list(@AuthenticationPrincipal TokenPrincipal token) {
        return filters.legacyList(FiltersController.viewer(token)).stream().map(V1FiltersController::response).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:filters')")
    @Operation(
            operationId = "getV1Filter",
            summary = "View a single filter (v1)",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#get-one-v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public FilterResponse.V1Response get(@AuthenticationPrincipal TokenPrincipal token,
                                         @Parameter(description = "The keyword's ID. " + ApiDocs.ID)
                                         @PathVariable String id) {
        var viewer = FiltersController.viewer(token);
        return RequestParameters.uuid(id).flatMap(keywordId -> filters.legacyFind(viewer, keywordId))
                .map(V1FiltersController::response).orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "createV1Filter",
            summary = "Create a filter (v1)",
            description = """
                    Makes a filter named after the phrase, with the phrase as its only keyword, as Mastodon does. \
                    """ + IRREVERSIBLE + " Answers 200 rather than 201, as Mastodon does.\n\n"
                    + FiltersController.HIDING + "\n\nDifferences from Mastodon: " + FiltersController.LIMITS
                    + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#create-v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new filter."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The phrase or context is missing, a value is refused, the user has as many filters as they \
                            can, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = V1FilterBody.class)))
    public FilterResponse.V1Response create(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = FiltersController.viewer(token);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        var contexts = FilterParameters.contexts(parameters);
        var irreversible = FilterParameters.flag(parameters, "irreversible");
        var wholeWord = FilterParameters.flag(parameters, "whole_word");
        return response(filters.legacyCreate(viewer, RequestParameters.string(parameters, "phrase"), contexts,
                Boolean.TRUE.equals(irreversible) ? PostFilter.Action.HIDE : PostFilter.Action.WARN,
                FilterParameters.expiry(parameters), Boolean.TRUE.equals(wholeWord)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "updateV1Filter",
            summary = "Update a filter (v1)",
            description = """
                    Changes the keyword, and what its filter applies to. What's left out stays as it is, and an empty \
                    `expires_in` makes the filter never expire. A new phrase also renames a filter that has only this \
                    keyword. """ + IRREVERSIBLE + """
                     The context, `irreversible`, and expiry of a filter with more than one keyword can't be changed \
                    here, as on Mastodon, since that would change its other keywords too.

                    Differences from Mastodon: the phrase and `whole_word` of a keyword in such a filter can still be \
                    changed, and `phrase` and `context` aren't required.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#update-v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The filter, as changed."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            A value is refused, the change would apply to the filter's other keywords too, or the \
                            token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = V1FilterBody.class)))
    public FilterResponse.V1Response update(@AuthenticationPrincipal TokenPrincipal token,
                                            @Parameter(description = "The keyword's ID. " + ApiDocs.ID)
                                            @PathVariable String id,
                                            HttpServletRequest request) throws IOException {
        var viewer = FiltersController.viewer(token);
        var keywordId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var parameters = json.readAny(request, MAX_BODY_BYTES);
        var phrase = parameters.containsKey("phrase") && RequestParameters.string(parameters, "phrase") == null
                ? "" : RequestParameters.string(parameters, "phrase");
        return filters.legacyUpdate(viewer, keywordId, phrase, FilterParameters.contexts(parameters),
                        FilterParameters.flag(parameters, "irreversible"),
                        FilterParameters.expiry(parameters), FilterParameters.flag(parameters, "whole_word"))
                .map(V1FiltersController::response).orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:filters')")
    @Operation(
            operationId = "deleteV1Filter",
            summary = "Remove a filter (v1)",
            description = """
                    Removes the keyword.

                    Differences from Mastodon: if the filter has nothing else left, no other keywords and no statuses, \
                    it's removed too, where Mastodon keeps it empty.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "filters/#delete-v1"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public Map<String, Object> delete(@AuthenticationPrincipal TokenPrincipal token,
                                      @Parameter(description = "The keyword's ID. " + ApiDocs.ID)
                                      @PathVariable String id) {
        var viewer = FiltersController.viewer(token);
        if (!RequestParameters.uuid(id).map(keywordId -> filters.legacyDelete(viewer, keywordId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    private static FilterResponse.V1Response response(PostFilters.LegacyFilter filter) {
        return FilterResponse.V1Response.from(filter.filter(), filter.keyword());
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "V1FilterBody")
    private record V1FilterBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The keyword, of up to\s""" + PostFilters.MAX_KEYWORD_LENGTH + " characters.")
            String phrase,
            @ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    Where it applies. Sent as `context[]` in a form."""),
                    schema = @Schema(allowableValues = {"home", "notifications", "public", "thread", "account"}))
            List<String> context,
            @Schema(description = "Whether the filter hides what it matches.", defaultValue = "false")
            Boolean irreversible,
            @Schema(description = "Whether it only matches whole words.", defaultValue = "false") Boolean whole_word,
            @Schema(description = "How many seconds until it expires. Left out or empty, it never does.")
            Integer expires_in) {
    }
}
