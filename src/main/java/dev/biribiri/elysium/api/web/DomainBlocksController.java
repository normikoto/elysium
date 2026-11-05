package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlockConflictException;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.InvalidDomainBlockException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
    public DomainBlockResponse get(@PathVariable String id) {
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
    // The API docs combine both mappings into one operation, and this replaces the body documented by the other one.
    @RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object"))
    })
    public DomainBlockResponse createFromJson(HttpServletRequest request) throws IOException {
        return create(json.read(request, MAX_BODY_BYTES));
    }

    @PutMapping(path = "/{id}", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    public DomainBlockResponse updateFromForm(@PathVariable String id, @RequestParam MultiValueMap<String, String> form) {
        return update(id, form.toSingleValueMap());
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    // The API docs combine both mappings into one operation, and this replaces the body documented by the other one.
    @RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object"))
    })
    public DomainBlockResponse updateFromJson(@PathVariable String id, HttpServletRequest request) throws IOException {
        return update(id, json.read(request, MAX_BODY_BYTES));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin:write:domain_blocks')")
    public Map<String, Object> delete(@PathVariable String id) {
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
            throw new InvalidRequestException(
                    "Validation failed: severity is required, and has to be " + DomainBlockResponse.SEVERITY);
        }
        checkSeverity(severity);
        return DomainBlockResponse.from(blocks.create(
                RequestParameters.string(parameters, "domain"), RequestParameters.string(parameters, "private_comment")));
    }

    /**
     * A block's domain can't be changed, as on Mastodon. Only a comment that's left out stays as it is, since an empty one
     * clears it.
     */
    private DomainBlockResponse update(String id, Map<String, ?> parameters) {
        checkSeverity(RequestParameters.string(parameters, "severity"));
        var comment = RequestParameters.string(parameters, "private_comment");
        var blockId = RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
        var updated = parameters.containsKey("private_comment")
                ? blocks.updateComment(blockId, comment)
                : blocks.find(blockId);
        return updated.map(DomainBlockResponse::from).orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Silencing isn't implemented. Making a full block instead would cut off a server that an admin only meant to limit.
     */
    private static void checkSeverity(String severity) {
        if (severity != null && !severity.equals(DomainBlockResponse.SEVERITY)) {
            throw new InvalidRequestException(
                    "Validation failed: severity has to be " + DomainBlockResponse.SEVERITY + ", the only one supported");
        }
    }
}
