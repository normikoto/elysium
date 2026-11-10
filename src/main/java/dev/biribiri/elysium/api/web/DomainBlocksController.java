package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlockConflictException;
import dev.biribiri.elysium.moderation.DomainBlockPage;
import dev.biribiri.elysium.moderation.DomainBlocks;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Follows Mastodon's admin API for domain blocks, except that every block is a full one.
 */
@RestController
@RequestMapping(DomainBlocksController.PATH)
@Tag(name = "Admin: domain blocks", description = """
        Blocking or limiting other servers, as `suspend` or `noop` blocks. Needs an admin's token.""",
        externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/"))
public class DomainBlocksController {

    static final String PATH = "/api/v1/admin/domain_blocks";

    /**
     * Room for the longest comment, in which a character can take up 12 bytes of JSON, and the other fields.
     */
    private static final long MAX_BODY_BYTES = DomainBlocks.MAX_COMMENT_LENGTH * 12L + 4 * 1024;

    private final DomainBlocks blocks;
    private final InstanceProperties instance;
    private final JsonBodies json;

    DomainBlocksController(DomainBlocks blocks, InstanceProperties instance, JsonBodies json) {
        this.blocks = blocks;
        this.instance = instance;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_admin:read:domain_blocks')")
    @Operation(
            operationId = "listDomainBlocks",
            summary = "List blocked domains",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/#get"),
            parameters = {
                    @Parameter(name = "limit", description = "How many to return. Values outside 1 to "
                            + DomainBlockPage.MAX_LIMIT + " are clamped to them.",
                            schema = @Schema(type = "integer", defaultValue = "" + DomainBlockPage.DEFAULT_LIMIT))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "The blocks, newest first.")
            })
    public ResponseEntity<List<DomainBlockResponse>> list(
            @RequestParam(required = false) String limit,
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(name = "since_id", required = false) String sinceId,
            @RequestParam(name = "min_id", required = false) String minId) {
        var page = RequestParameters.domainBlockPage(limit, maxId, sinceId, minId);
        var found = blocks.list(page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, PATH, Map.of(), page.limit(), found.stream().map(DomainBlock::id).toList());
        return response.body(found.stream().map(DomainBlockResponse::from).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:read:domain_blocks')")
    @Operation(
            operationId = "getDomainBlock",
            summary = "View a domain block",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/#get-one"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The block."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public DomainBlockResponse get(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        return RequestParameters.uuid(id)
                .flatMap(blocks::find)
                .map(DomainBlockResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    public DomainBlockResponse createFromForm(@RequestParam MultiValueMap<String, String> form) {
        return create(form.toSingleValueMap());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "createDomainBlock",
            summary = "Block a domain",
            description = """
                    Differences from Mastodon:

                    - `severity` is required, and has to be `suspend` or `noop`. Mastodon's default is `silence`, \
                    which isn't supported, and gets a 422 here, rather than a block that does more or less than an \
                    admin meant.
                    - `reject_media` keeps a `noop` block's server's attachments from being downloaded, and removes \
                    the copies already kept. A suspension rejects them anyway. `reject_reports`, `obfuscate`, and \
                    `public_comment` are ignored.
                    - A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a \
                    domain with only one label such as `com`, and this server's own domain or one above it. Blocks \
                    always cover subdomains and every port. A Unicode domain that converting would change, such as \
                    `faß.example`, has to be sent in its `xn--` form.
                    - A domain that a broader block already covers gets a 422 with `existing_domain_block`, like one \
                    that's already blocked, unless the new block does more, such as suspending a subdomain of a \
                    `noop` block.
                    - `private_comment` can be up to\s""" + DomainBlocks.MAX_COMMENT_LENGTH + """
                     characters.

                    A suspension removes follows to and from the server's accounts, which come back only if they're \
                    made again. A `noop` block doesn't stop the server federating.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The new block."),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = """
                            The domain or severity is missing or refused, or the domain is already blocked, in which \
                            case the body also has the block as `existing_domain_block`.""")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NewDomainBlockBody.class)))
    public DomainBlockResponse createFromJson(HttpServletRequest request) throws IOException {
        return create(json.read(request, MAX_BODY_BYTES));
    }

    @PutMapping(path = "/{id}", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    public DomainBlockResponse updateFromForm(
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            @RequestParam MultiValueMap<String, String> form) {
        return update(id, form.toSingleValueMap());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    // The API docs combine both mappings into one operation, and this replaces what the other one documents.
    @Operation(
            operationId = "updateDomainBlock",
            summary = "Change a domain block",
            description = """
                    `severity`, `reject_media`, and `private_comment` can be changed, and what's left out stays as it \
                    is, as on Mastodon. Making a block a suspension removes what's stored from its server, and \
                    rejecting media removes the copies of its attachments. Neither comes back if it's undone, though \
                    attachments are shown as links again.

                    Differences from Mastodon: `severity` has to be `suspend` or `noop`, an empty `private_comment` \
                    removes it, and other fields are ignored. A block's domain can't be changed, as on Mastodon.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/#update"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The block, as changed."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND),
                    @ApiResponse(responseCode = "413", description = "The body is too large."),
                    @ApiResponse(responseCode = "422", description = "The severity or comment is refused.")
            })
    @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DomainBlockChangeBody.class)))
    public DomainBlockResponse updateFromJson(
            @Parameter(description = ApiDocs.ID) @PathVariable String id,
            HttpServletRequest request) throws IOException {
        return update(id, json.read(request, MAX_BODY_BYTES));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    @Operation(
            operationId = "deleteDomainBlock",
            summary = "Unblock a domain",
            description = """
                    Nothing that the block removed comes back. Accounts on that server have to follow again, and \
                    nothing they sent while it was blocked is delivered.""",
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "admin/domain_blocks/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "An empty object."),
                    @ApiResponse(responseCode = "404", description = ApiDocs.NOT_FOUND)
            })
    public Map<String, Object> delete(@Parameter(description = ApiDocs.ID) @PathVariable String id) {
        if (!RequestParameters.uuid(id).map(blocks::delete).orElse(false)) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalid(InvalidDomainBlockException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, Object>> conflict(DomainBlockConflictException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of(
                "error", e.getMessage(),
                "existing_domain_block", DomainBlockResponse.from(e.existing())));
    }

    /**
     * Mastodon's default severity is the milder {@code silence}, so a client that leaves it out isn't asking for what this
     * would do.
     */
    private DomainBlockResponse create(Map<String, ?> parameters) {
        var severity = RequestParameters.string(parameters, "severity");
        if (severity == null) {
            throw new InvalidRequestException("Validation failed: severity is required, and has to be suspend or noop");
        }
        return DomainBlockResponse.from(blocks.create(
                RequestParameters.string(parameters, "domain"), severity(severity),
                RequestParameters.flag(parameters, "reject_media"),
                RequestParameters.string(parameters, "private_comment")));
    }

    /**
     * A block's domain can't be changed, as on Mastodon. Only a comment that's left out stays as it is, since an empty one
     * clears it.
     */
    private DomainBlockResponse update(String id, Map<String, ?> parameters) {
        var severity = RequestParameters.string(parameters, "severity");
        var newSeverity = severity == null ? null : severity(severity);
        var blockId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var old = blocks.find(blockId).orElseThrow(RecordNotFoundException::new);
        var comment = parameters.containsKey("private_comment")
                ? RequestParameters.string(parameters, "private_comment")
                : old.privateComment();
        var rejectMedia = parameters.containsKey("reject_media")
                ? RequestParameters.flag(parameters, "reject_media")
                : old.rejectMedia();
        return blocks.update(blockId, newSeverity == null ? old.severity() : newSeverity, rejectMedia, comment)
                .map(DomainBlockResponse::from)
                .orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Silencing isn't implemented. Making a full block instead would cut off a server that an admin only meant to limit,
     * and ignoring it would do less than they meant.
     */
    private static DomainBlock.Severity severity(String severity) {
        try {
            return DomainBlock.Severity.fromValue(severity);
        } catch (InvalidDomainBlockException e) {
            throw new InvalidRequestException("Validation failed: severity has to be suspend or noop");
        }
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "NewDomainBlock")
    private record NewDomainBlockBody(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = """
                    The domain, which also covers its subdomains and every port.""")
            String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"suspend", "noop"}, description = """
                    `suspend` cuts the server off. `noop` only does what the block's other settings say.""")
            String severity,
            @Schema(description = "Whether to keep its attachments from being downloaded.", defaultValue = "false")
            Boolean reject_media,
            @Schema(description = "A note for admins, of up to " + DomainBlocks.MAX_COMMENT_LENGTH + " characters.")
            String private_comment) {
    }

    /**
     * Only documents the body, which is read by hand.
     */
    @Schema(name = "DomainBlockChange")
    private record DomainBlockChangeBody(
            @Schema(allowableValues = {"suspend", "noop"}, description = "Leaving it out keeps it.")
            String severity,
            @Schema(description = "Whether to keep its attachments from being downloaded. Leaving it out keeps it.")
            Boolean reject_media,
            @Schema(description = "A note for admins, of up to " + DomainBlocks.MAX_COMMENT_LENGTH
                    + " characters. An empty value removes it, and leaving it out keeps it.")
            String private_comment) {
    }
}
