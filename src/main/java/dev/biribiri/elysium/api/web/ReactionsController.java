package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.InvalidReactionException;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.ReactionSummary;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Pleroma's and Akkoma's emoji reactions API, which is what clients that show reactions use, since Mastodon has none.
 */
@RestController
@RequestMapping(ReactionsController.PATH)
@Tag(name = "Emoji reactions", description = "Pleroma's emoji reactions to statuses.",
        externalDocs = @ExternalDocumentation(url = ReactionsController.DOCS))
public class ReactionsController {

    static final String PATH = "/api/v1/pleroma/statuses/{id}/reactions";

    static final String DOCS = "https://docs.akkoma.dev/stable/development/API/pleroma_api/#emoji-reactions";

    private static final String EMOJI = """
            One Unicode emoji, the shortcode of one of this server's custom emoji, with or without colons, or \
            `shortcode@domain` for another server's emoji that this server has. Escape it as a path segment.""";

    private static final String CHOOSING = """
            Differences from Pleroma: an account can react to a status with at most\s""" + ReactionService.MAX_PER_ACCOUNT
            + """
             emoji, and a custom emoji from another server can be used once this server has it, which it does once a \
            status or a reaction here has used it.""";

    private final ReactionService reactions;
    private final BoostService boosts;
    private final StatusService statuses;
    private final StatusResponses responses;
    private final ActorService actors;
    private final AccountResponses accounts;
    private final InstanceProperties instance;

    ReactionsController(ReactionService reactions, BoostService boosts, StatusService statuses,
                        StatusResponses responses, ActorService actors, AccountResponses accounts,
                        InstanceProperties instance) {
        this.reactions = reactions;
        this.boosts = boosts;
        this.statuses = statuses;
        this.responses = responses;
        this.actors = actors;
        this.accounts = accounts;
        this.instance = instance;
    }

    @PutMapping("/{emoji}")
    @PreAuthorize("hasAnyAuthority('SCOPE_write:favourites', 'SCOPE_write:statuses')")
    @Operation(operationId = "reactToStatus", summary = "React to a status with an emoji",
            description = """
                    Reacts to a visible status, which a boost's ID stands for, and sends the reaction to its author's \
                    server as an `EmojiReact`. Doing it again has no more effect. An account can react with several \
                    emoji, and a reaction isn't a favourite. It needs `write:favourites` or `write:statuses`.

                    """ + CHOOSING + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS),
            parameters = {
                    @Parameter(name = "id", description = ApiDocs.ID),
                    @Parameter(name = "emoji", description = EMOJI)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, with its reactions."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = """
                            It isn't an emoji that can be used, the account has reacted with as many emoji as it can, \
                            or the token has no user.""")
            })
    public StatusResponse react(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
                                @PathVariable String emoji) {
        return change(token, id, emoji, (statusId, change) -> reactions.react(statusId, change.actorId, change.choice));
    }

    @DeleteMapping("/{emoji}")
    @PreAuthorize("hasAnyAuthority('SCOPE_write:favourites', 'SCOPE_write:statuses')")
    @Operation(operationId = "unreactToStatus", summary = "Remove a reaction to a status",
            description = """
                    Removes the account's reaction with an emoji, and sends an `Undo` to the status's author's server. \
                    Doing it again, or for an emoji the account didn't react with, has no more effect.\n\n""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = DOCS),
            parameters = {
                    @Parameter(name = "id", description = ApiDocs.ID),
                    @Parameter(name = "emoji", description = EMOJI)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status, with its reactions."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "422", description = """
                            It isn't an emoji that can be used, or the token has no user.""")
            })
    public StatusResponse unreact(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
                                  @PathVariable String emoji) {
        return change(token, id, emoji, (statusId, change) -> reactions.unreact(statusId, change.actorId, change.choice));
    }

    @GetMapping
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "listReactions", summary = "View a status's reactions",
            description = """
                    Each emoji the status has been reacted with, in the order each was first used, with the accounts \
                    that reacted with it. It needs no token for a status anyone can see. Differences from Pleroma: \
                    `accounts` has at most\s""" + ReactionService.MAX_LISTED_ACCOUNTS + """
                     of each emoji's accounts, the most recent first, and only reactions to statuses on this server \
                    from other servers are known here, apart from those of this server's own accounts.""",
            externalDocs = @ExternalDocumentation(url = DOCS),
            parameters = @Parameter(name = "id", description = ApiDocs.ID),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status's reactions."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public List<ReactionResponse> list(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        return listed(token, id, summary -> true);
    }

    @GetMapping("/{emoji}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "listReactionsWithEmoji", summary = "View a status's reactions with one emoji",
            description = """
                    Like listing all of them, but only with one emoji, so the list is empty or has one entry.""",
            externalDocs = @ExternalDocumentation(url = DOCS),
            parameters = {
                    @Parameter(name = "id", description = ApiDocs.ID),
                    @Parameter(name = "emoji", description = EMOJI)
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The status's reactions with the emoji."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public List<ReactionResponse> listOne(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id,
                                          @PathVariable String emoji) {
        Optional<ReactionService.Choice> choice;
        try {
            choice = Optional.of(reactions.choose(emoji));
        } catch (InvalidReactionException e) {
            choice = Optional.empty();
        }
        var name = choice.map(found -> ReactionService.nameOf(found.name(), found.emoji()));
        return listed(token, id, summary -> name.filter(summary.name()::equals).isPresent());
    }

    private record Change(UUID actorId, ReactionService.Choice choice) {
    }

    private StatusResponse change(TokenPrincipal token, String id, String emoji,
                                  BiFunction<UUID, Change, Optional<Status>> action) {
        var actorId = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var statusId = RequestParameters.uuid(id).flatMap(boosts::original).orElseThrow(RecordNotFoundException::new);
        try {
            var change = new Change(actorId, reactions.choose(emoji));
            return action.apply(statusId, change)
                    .map(found -> responses.one(found, Optional.of(actorId)))
                    .orElseThrow(RecordNotFoundException::new);
        } catch (InvalidReactionException e) {
            throw new InvalidRequestException("Validation failed: " + e.getMessage());
        }
    }

    private List<ReactionResponse> listed(TokenPrincipal token, String id,
                                          Predicate<ReactionSummary> shown) {
        var viewer = RequestParameters.viewer(token);
        var status = RequestParameters.uuid(id).flatMap(boosts::original)
                .flatMap(statusId -> statuses.findVisible(statusId, viewer))
                .orElseThrow(RecordNotFoundException::new);
        var found = reactions.withAccounts(status.id(), viewer).stream().filter(shown).toList();
        var accountsById = accounts.byId(actors.findByIds(found.stream()
                        .flatMap(summary -> summary.accountIds().stream()).distinct().toList()).stream()
                .filter(Actor::isActive).toList());
        return found.stream().map(summary -> ReactionResponse.of(summary, instance, summary.accountIds().stream()
                .map(accountsById::get).filter(Objects::nonNull).toList())).toList();
    }
}
