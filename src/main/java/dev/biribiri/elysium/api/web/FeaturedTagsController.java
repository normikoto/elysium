package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.text.Hashtags;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@Tag(name = "Featured tags", description = """
        The hashtags accounts feature on their profiles. An account on another server features what its server \
        last listed in its `featuredTags` collection. Local users' featured tags aren't sent to other servers.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "featured_tags/"))
public class FeaturedTagsController {

    private static final String PATH = "/api/v1/featured_tags";

    /**
     * Room for the longest name, in which a character can take up 12 bytes of JSON, with plenty to spare.
     */
    private static final long MAX_BODY_BYTES = 4 * 1024;

    private static final String COUNTS = """
            `statuses_count` and `last_status_at` count the account's public and unlisted statuses with the tag, \
            boosts left out.""";

    private final FeaturedTags featured;
    private final TagResponses tags;
    private final ActorService actors;
    private final JsonBodies json;
    private final InstanceProperties instance;

    FeaturedTagsController(FeaturedTags featured, TagResponses tags, ActorService actors, JsonBodies json,
                           InstanceProperties instance) {
        this.featured = featured;
        this.tags = tags;
        this.actors = actors;
        this.json = json;
        this.instance = instance;
    }

    @GetMapping(PATH)
    @PreAuthorize("hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getFeaturedTags",
            summary = "List the hashtags the user features",
            description = COUNTS + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "featured_tags/#get"),
            responses = {
                    @ApiResponse(responseCode = "200", description = """
                            The tags, most used first, all in one page."""),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public List<FeaturedTagResponse> list(@AuthenticationPrincipal TokenPrincipal token) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        return responses(viewer);
    }

    @PostMapping(PATH)
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "featureTag",
            summary = "Feature a hashtag on the user's profile",
            description = """
                    Up to\s""" + FeaturedTags.MAX_PER_ACCOUNT + """
                     tags can be featured, as on Mastodon. Any valid tag name can be featured, even one the user \
                    hasn't used. Featuring a tag again returns it, with no additional effect.

                    """ + COUNTS + "\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "featured_tags/#feature"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The featured tag."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            `name` is missing or isn't a valid tag name, the user has featured as many tags as they \
                            can, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FeatureBody.class)))
    public FeaturedTagResponse feature(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        var given = RequestParameters.string(json.readAny(request, MAX_BODY_BYTES), "name");
        if (given == null || given.isBlank()) {
            throw new InvalidRequestException("Validation failed: Name can't be blank");
        }
        var name = Hashtags.parse(given.strip())
                .orElseThrow(() -> new InvalidRequestException("Validation failed: Tag is invalid"));
        return FeaturedTagResponse.from(featured.feature(viewer, name), instance);
    }

    @DeleteMapping(PATH + "/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:accounts')")
    @Operation(
            operationId = "unfeatureTag",
            summary = "Stop featuring a hashtag",
            description = ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "featured_tags/#unfeature"),
            parameters = @Parameter(name = "id", description = "The featured tag's ID, not its name. " + ApiDocs.ID),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = """
                            The user doesn't feature a tag with this ID, or the ID is malformed."""),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public Map<String, Object> unfeature(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        if (!RequestParameters.uuid(id).map(featuredId -> featured.unfeature(viewer, featuredId)).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @GetMapping(PATH + "/suggestions")
    @PreAuthorize("hasAuthority('SCOPE_read:accounts')")
    @Operation(
            operationId = "getFeaturedTagSuggestions",
            summary = "Suggest hashtags for the user to feature",
            description = """
                    Up to\s""" + FeaturedTags.MAX_SUGGESTIONS + """
                     tags from the user's recent public and unlisted statuses that they don't feature, the most \
                    recently used first.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "featured_tags/#suggestions"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tags, as Tag entities."),
                    @ApiResponse(responseCode = "422", description = "The token does not identify a user.")
            })
    public List<TagResponse> suggestions(@AuthenticationPrincipal TokenPrincipal token) {
        var viewer = token.actorId().orElseThrow(InvalidRequestException::requiresUser);
        return tags.build(featured.suggestions(viewer), Optional.of(viewer));
    }

    /**
     * Mastodon needs no scope for this, so any token works, as does none.
     */
    @GetMapping("/api/v1/accounts/{id}/featured_tags")
    @PreAuthorize("isAnonymous() or isAuthenticated()")
    @Operation(
            operationId = "getAccountFeaturedTags",
            summary = "List the hashtags an account features",
            description = """
                    An account on another server features what its server last listed, and its counts only include \
                    its statuses stored here.

                    """ + COUNTS + " Any token works, with any scope, as does none. A suspended account gets 404.",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "accounts/#featured_tags"),
            parameters = @Parameter(name = "id", description = ApiDocs.ID),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The tags, most used first, all in one page."),
                    @ApiResponse(responseCode = "404", description = "Not found.")
            })
    public List<FeaturedTagResponse> forAccount(@PathVariable String id) {
        var account = RequestParameters.uuid(id)
                .flatMap(actors::findById)
                .filter(Actor::isActive)
                .orElseThrow(RecordNotFoundException::new);
        return responses(account.id());
    }

    private List<FeaturedTagResponse> responses(UUID accountId) {
        return featured.featured(accountId).stream().map(tag -> FeaturedTagResponse.from(tag, instance)).toList();
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "FeatureTag")
    private record FeatureBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The tag's name, with or without its `#`.")
            String name) {
    }
}
