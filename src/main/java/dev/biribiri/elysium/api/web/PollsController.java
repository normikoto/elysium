package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.RemotePollRefresher;
import dev.biribiri.elysium.content.StatusService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/polls")
@Tag(name = "Polls", description = "Viewing and voting in polls.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "polls/"))
public class PollsController {

    /**
     * Room for a vote's choices, and anything else a client sends along with them.
     */
    private static final long VOTE_BODY_BYTES = 4 * 1024;

    private final StatusService statuses;
    private final PollService polls;
    private final RemotePollRefresher refresher;
    private final JsonBodies json;

    PollsController(StatusService statuses, PollService polls, RemotePollRefresher refresher, JsonBodies json) {
        this.statuses = statuses;
        this.polls = polls;
        this.refresher = refresher;
        this.json = json;
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(
            operationId = "getPoll",
            summary = "View a poll",
            description = """
                    Its ID is its status's, and it's found for whoever can see the status, so without a token for a \
                    public or unlisted one. A poll from another server whose counts are more than a few minutes old is \
                    fetched again from there in the background, so a later request has the new counts.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "polls/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The poll."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the status has no poll, or the user can't see it.""")
            })
    public PollResponse get(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id) {
        var viewer = RequestParameters.viewer(token);
        var statusId = visible(id, viewer);
        var poll = polls.find(statusId, viewer).orElseThrow(RecordNotFoundException::new);
        var now = Instant.now();
        if (!poll.local() && polls.isStale(statusId, now)) {
            refresher.refreshSoon(statusId);
        }
        return PollResponse.from(poll, now);
    }

    @PostMapping("/{id}/votes")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(
            operationId = "votePoll",
            summary = "Vote in a poll",
            description = """
                    Votes once, for one option, or for any number of them in a poll that allows that. `choices[]` \
                    can be sent as a form, a query parameter, or JSON. A vote in a poll from another server is sent \
                    there, and its counts here include the vote until that server sends new ones.

                    Differences from Mastodon:

                    - A choice that isn't an option, one given twice, more than one in a poll that only allows one, \
                    and none at all each get a 422.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "polls/#vote"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The poll, with the user's votes."),
                    @ApiResponse(responseCode = "404", description = """
                            Not found, the status has no poll, or the user can't see it."""),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The poll has ended, the user has voted in it already, the choices don't fit it, or the \
                            token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = VoteBody.class)))
    public PollResponse vote(
            @AuthenticationPrincipal TokenPrincipal token, @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        var voter = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var choices = choices(json.readAny(request, VOTE_BODY_BYTES));
        var statusId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        return polls.vote(statusId, voter, choices)
                .map(poll -> PollResponse.from(poll, Instant.now()))
                .orElseThrow(RecordNotFoundException::new);
    }

    private UUID visible(String id, Optional<UUID> viewer) {
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.findVisible(statusId, viewer))
                .orElseThrow(RecordNotFoundException::new)
                .id();
    }

    private static List<Integer> choices(Map<String, Object> parameters) {
        var value = parameters.containsKey("choices") ? parameters.get("choices") : parameters.get("choices[]");
        List<?> values = switch (value) {
            case null -> List.of();
            case List<?> list -> list;
            default -> List.of(value);
        };
        var choices = new ArrayList<Integer>();
        for (var choice : values) {
            choices.add(switch (choice) {
                case Integer number -> number;
                case String string when !string.isEmpty() -> RequestParameters.number("choices", string, -1);
                default -> throw invalidChoices();
            });
        }
        return choices;
    }

    private static InvalidRequestException invalidChoices() {
        return new InvalidRequestException("Validation failed: choices must be a list of numbers");
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "PollVote")
    private record VoteBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The positions of the options chosen, starting at 0.""")
            List<Integer> choices) {
    }
}
