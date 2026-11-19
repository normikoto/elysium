package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.content.StatusService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * GoToSocial's API for approving interactions by hand, which Mastodon doesn't have. Only quotes are approved by hand here,
 * so it adds a {@code quote} type, and lists nothing for the others.
 */
@RestController
@RequestMapping("/api/v1/interaction_requests")
@Tag(name = "Interaction requests", description = """
        Not in Mastodon. GoToSocial's API for approving or rejecting quotes of the user's statuses by hand, for statuses \
        whose quotes need approval.""",
        externalDocs = @ExternalDocumentation(url = InteractionRequestsController.GOTOSOCIAL_DOCS))
public class InteractionRequestsController {

    static final String GOTOSOCIAL_DOCS = "https://docs.gotosocial.org/en/latest/api/swagger/";

    private static final String NO_REQUEST = """
            Not found, the quoted status isn't the user's, or the ID isn't a UUID.""";

    private final QuoteService quotes;
    private final StatusService statuses;
    private final ActorService actors;
    private final StatusResponses statusResponses;
    private final AccountResponses accounts;
    private final InstanceProperties instance;

    InteractionRequestsController(
            QuoteService quotes,
            StatusService statuses,
            ActorService actors,
            StatusResponses statusResponses,
            AccountResponses accounts,
            InstanceProperties instance) {
        this.quotes = quotes;
        this.statuses = statuses;
        this.actors = actors;
        this.statusResponses = statusResponses;
        this.accounts = accounts;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(
            operationId = "getInteractionRequests",
            summary = "List the quotes waiting for the user to review them",
            description = """
                    Differences from GoToSocial: only quotes are approved by hand, so this only lists quotes, which \
                    have the type `quote`, and nothing when `quotes` is false. `favourites`, `replies`, and `reblogs` \
                    are accepted, and change nothing. Only quotes still waiting are listed. Pages are by when the \
                    quote was posted, and default to 20 and allow up to 40.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = GOTOSOCIAL_DOCS),
            parameters = {
                    @Parameter(name = "status_id", description = "Only quotes of this status of the user's."),
                    @Parameter(name = "quotes", description = "Not in GoToSocial. Whether to list quotes.",
                            schema = @Schema(type = "boolean", defaultValue = "true")),
                    @Parameter(name = "favourites", description = "Ignored, since favourites aren't approved by hand.",
                            schema = @Schema(type = "boolean")),
                    @Parameter(name = "replies", description = "Ignored, since replies aren't approved by hand.",
                            schema = @Schema(type = "boolean")),
                    @Parameter(name = "reblogs", description = "Ignored, since boosts aren't approved by hand.",
                            schema = @Schema(type = "boolean")),
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + StatusPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + StatusPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The quotes, newest first."),
                    @ApiResponse(responseCode = "422", description = """
                            A cursor or `status_id` isn't a UUID, or the token has no user.""")
            })
    public ResponseEntity<List<InteractionRequestResponse>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(name = "status_id", required = false) String statusId,
            @RequestParam(required = false) String quotes,
            @RequestParam(required = false) String favourites,
            @RequestParam(required = false) String replies,
            @RequestParam(required = false) String reblogs,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = viewer(token);
        var quoted = statusId == null || statusId.isEmpty() ? null : RequestParameters.uuid(statusId)
                .orElseThrow(() -> new InvalidRequestException("Validation failed: status_id must be an ID"));
        var page = RequestParameters.page(limit, maxId, sinceId, minId);
        var listQuotes = quotes == null || quotes.isEmpty() || RequestParameters.flag(quotes);
        var found = listQuotes ? this.quotes.findReviews(viewer, quoted, page) : List.<StatusQuote>of();
        var filters = new HashMap<String, Object>();
        if (quoted != null) {
            filters.put("status_id", quoted);
        }
        if (!listQuotes) {
            filters.put("quotes", false);
        }
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/interaction_requests", filters, page.limit(),
                found.stream().map(StatusQuote::statusId).toList());
        return response.body(render(found, viewer));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_read:notifications')")
    @Operation(
            operationId = "getInteractionRequest",
            summary = "Show a quote of one of the user's statuses, for reviewing it",
            description = """
                    Differences from GoToSocial: the ID is the quote's, and any quote of the user's statuses can be \
                    shown, waiting or not.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = GOTOSOCIAL_DOCS),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The request."),
                    @ApiResponse(responseCode = "404", description = NO_REQUEST),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public InteractionRequestResponse one(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(quoting -> quotes.findReview(quoting, viewer))
                .flatMap(quote -> render(quote, viewer))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/authorize")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "authorizeInteractionRequest",
            summary = "Approve a quote of one of the user's statuses",
            description = """
                    Differences from GoToSocial: a quote the user rejected can be approved later, and one that's \
                    approved already is returned as it is. A quote from another server is sent an `Accept`. The \
                    user is notified of the quote, as for one approved automatically.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = GOTOSOCIAL_DOCS),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The request, now approved."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the quoted status isn't the user's, the quote was revoked or is waiting for \
                            nothing, or the status's policy no longer allows its author to quote it."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public InteractionRequestResponse authorize(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(quoting -> quotes.authorizeReview(quoting, viewer))
                .flatMap(quote -> render(quote, viewer))
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "rejectInteractionRequest",
            summary = "Reject a quote of one of the user's statuses",
            description = """
                    Differences from GoToSocial: a quote from another server is sent a `Reject`, and a rejected quote \
                    is returned as it is. To withdraw an approval, revoke the quote instead.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = GOTOSOCIAL_DOCS),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The request, now rejected."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the quoted status isn't the user's, or the quote isn't waiting for the user."""),
                    @ApiResponse(responseCode = "422", description = "The token has no user.")
            })
    public InteractionRequestResponse reject(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = viewer(token);
        return RequestParameters.uuid(id).flatMap(quoting -> quotes.rejectReview(quoting, viewer))
                .flatMap(quote -> render(quote, viewer))
                .orElseThrow(RecordNotFoundException::new);
    }

    private Optional<InteractionRequestResponse> render(StatusQuote quote, UUID viewer) {
        return render(List.of(quote), viewer).stream().findFirst();
    }

    /**
     * With one lookup of each kind for the whole page.
     *
     * @return a response for each quote whose statuses, and whose author, can still be shown, in the same order
     */
    private List<InteractionRequestResponse> render(List<StatusQuote> found, UUID viewer) {
        var ids = new LinkedHashSet<UUID>();
        found.forEach(quote -> {
            ids.add(quote.statusId());
            if (quote.quotedStatusId() != null) {
                ids.add(quote.quotedStatusId());
            }
        });
        var stored = statuses.findStored(ids);
        var responses = statusResponses.list(stored, Optional.of(viewer), Optional.empty()).stream()
                .collect(Collectors.toMap(StatusResponse::id, Function.identity()));
        var authorIds = stored.stream().collect(Collectors.toMap(Status::id, Status::actorId));
        var authors = accounts.byId(actors.findByIds(Set.copyOf(authorIds.values())));
        var rendered = new ArrayList<InteractionRequestResponse>();
        for (var quote : found) {
            var quoting = responses.get(quote.statusId().toString());
            var quoted = quote.quotedStatusId() == null ? null : responses.get(quote.quotedStatusId().toString());
            var author = quoting == null ? null : authors.get(authorIds.get(quote.statusId()));
            if (quoted != null && author != null) {
                rendered.add(InteractionRequestResponse.of(quote, author, quoted, quoting));
            }
        }
        return rendered;
    }

    private static UUID viewer(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }
}
