package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.graph.AccountDomainBlockService;
import dev.biribiri.elysium.graph.DomainBlockListPage;
import dev.biribiri.elysium.moderation.DomainName;
import dev.biribiri.elysium.moderation.InvalidDomainBlockException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A user's own blocks of other servers, which are separate from the admin's domain blocks. Parameters are read from a form,
 * the query, or a JSON body, since clients send each.
 */
@RestController
@RequestMapping(UserDomainBlocksController.PATH)
@Tag(name = "Domain blocks", description = "The servers the user has blocked.",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "domain_blocks/"))
public class UserDomainBlocksController {

    static final String PATH = "/api/v1/domain_blocks";

    /**
     * Room for the longest domain, with plenty to spare.
     */
    private static final long MAX_BODY_BYTES = 4 * 1024;

    private static final String DOMAIN_RULES = """
            A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a domain with only \
            one label such as `com`, and this server's own domain or one above it. A Unicode domain that converting \
            would change, such as `faß.example`, has to be sent in its `xn--` form.""";

    private final AccountDomainBlockService domainBlocks;
    private final InstanceProperties instance;
    private final JsonBodies json;

    UserDomainBlocksController(AccountDomainBlockService domainBlocks, InstanceProperties instance, JsonBodies json) {
        this.domainBlocks = domainBlocks;
        this.instance = instance;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:blocks')")
    @Operation(
            operationId = "getDomainBlocks",
            summary = "List the servers the user has blocked",
            description = """
                    Differences from Mastodon: pages are by when each block was made, and the cursors are the IDs in \
                    the `Link` header.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "domain_blocks/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + DomainBlockListPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + DomainBlockListPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The domains, most recent block first."),
                    @ApiResponse(responseCode = "422", description = "A cursor isn't a UUID, or the token has no user.")
            })
    public ResponseEntity<List<String>> list(
            @AuthenticationPrincipal TokenPrincipal token,
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var viewer = viewer(token);
        var page = RequestParameters.domainBlockListPage(limit, maxId, sinceId, minId);
        var found = domainBlocks.page(viewer, page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, Map.of(), page.limit(),
                found.stream().map(AccountDomainBlockService.Entry::id).toList());
        return response.body(found.stream().map(AccountDomainBlockService.Entry::domain).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_write:blocks')")
    @Operation(
            operationId = "blockDomain",
            summary = "Block a server",
            description = """
                    Hides the server's accounts from the user's timelines, threads, and notifications, removes the \
                    user's followers there, and refuses their requests to follow. The user's own follows of accounts \
                    there are kept, as on Mastodon. A block covers subdomains, and isn't sent to other servers, though \
                    each follower's server is told that its follow ended. Repeats have no additional effect.

                    Differences from Mastodon:\s""" + DOMAIN_RULES + """
                     Mastodon blocks only the domain given, without its subdomains.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "domain_blocks/#block"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The domain is missing or refused, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DomainBody.class)))
    public Map<String, Object> block(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = viewer(token);
        domainBlocks.block(viewer, domain(json.readAny(request, MAX_BODY_BYTES)));
        return Map.of();
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('SCOPE_write:blocks')")
    @Operation(
            operationId = "unblockDomain",
            summary = "Unblock a server",
            description = """
                    Followers that the block removed don't come back. A domain that isn't blocked gets an empty \
                    object too, as on Mastodon, while one that can't be blocked gets a 422.

                    """ + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "domain_blocks/#unblock"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The domain is missing or refused, or the token has no user.""")
            })
    @RequestBody(content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DomainBody.class)))
    public Map<String, Object> unblock(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var viewer = viewer(token);
        domainBlocks.unblock(viewer, domain(json.readAny(request, MAX_BODY_BYTES)));
        return Map.of();
    }

    private static UUID viewer(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    /**
     * Read as an admin's domain block is, so that the two match the same servers.
     */
    private String domain(Map<String, ?> parameters) {
        String name;
        try {
            name = DomainName.parse(RequestParameters.string(parameters, "domain"));
        } catch (InvalidDomainBlockException e) {
            throw new InvalidRequestException("Validation failed: " + e.getMessage());
        }
        var ownHost = DomainName.normalizeHost(instance.baseUrl().getHost());
        if (ownHost.isPresent() && (ownHost.get().equals(name) || ownHost.get().endsWith("." + name))) {
            throw new InvalidRequestException("Validation failed: this server's own domain can't be blocked");
        }
        return name;
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "UserDomainBlock")
    private record DomainBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The domain to block or unblock.")
            String domain) {
    }
}
