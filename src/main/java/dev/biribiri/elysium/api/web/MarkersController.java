package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.inbox.InboxService;
import dev.biribiri.elysium.inbox.Marker;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/markers")
@Tag(name = "Markers", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "markers/"))
@ApiResponse(responseCode = "422", description = "The token has no user, or a position is invalid.")
public class MarkersController {
    private final InboxService inbox;
    private final JsonBodies json;

    MarkersController(InboxService inbox, JsonBodies json) {
        this.inbox = inbox;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "getMarkers", summary = "Get saved timeline positions",
            description = "Returns only existing markers for the requested timelines; without timeline[] returns {}.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "markers/#get"),
            responses = @ApiResponse(responseCode = "200", description = "Markers keyed by home or notifications."))
    public Map<String, MarkerResponse> get(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(description = "Timelines to fetch: home and/or notifications.")
            @RequestParam(name = "timeline[]", required = false) List<String> timelines) {
        return responses(inbox.markers(NotificationRequests.viewer(token), timelines == null ? List.of() : timelines));
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public Map<String, MarkerResponse> saveForm(@AuthenticationPrincipal TokenPrincipal token,
            @RequestParam MultiValueMap<String, String> form) {
        var viewer = NotificationRequests.viewer(token);
        var positions = new HashMap<String, UUID>();
        for (var timeline : List.of("home", "notifications")) {
            var value = form.getFirst(timeline + "[last_read_id]");
            if (value != null) positions.put(timeline, position(value));
        }
        return responses(inbox.saveMarkers(viewer, positions));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    @Operation(operationId = "saveMarkers", summary = "Save timeline positions",
            description = "A position must be a UUID. Updates are atomic across timelines; concurrent changes can return 409.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "markers/#create"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The updated markers, including version and updated_at."),
                    @ApiResponse(responseCode = "409", description = "A concurrent update changed a marker; retry the request."),
                    @ApiResponse(responseCode = "413", description = "The body is larger than 64 KiB.")
            })
    @RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MarkerBody.class)))
    public Map<String, MarkerResponse> saveJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request) throws IOException {
        var viewer = NotificationRequests.viewer(token);
        var body = json.read(request, 64 * 1024);
        var positions = new HashMap<String, UUID>();
        for (var timeline : List.of("home", "notifications")) {
            var value = body.get(timeline);
            if (value == null) continue;
            if (!(value instanceof Map<?, ?> fields)) throw new InvalidRequestException("Validation failed: " + timeline + " must be an object");
            if (fields.containsKey("last_read_id")) positions.put(timeline, position(fields.get("last_read_id")));
        }
        return responses(inbox.saveMarkers(viewer, positions));
    }

    private static UUID position(Object value) {
        if (value instanceof String string) {
            return RequestParameters.uuid(string).orElseThrow(() -> new InvalidRequestException("Validation failed: last_read_id must be a UUID"));
        }
        throw new InvalidRequestException("Validation failed: last_read_id must be a UUID");
    }

    private static Map<String, MarkerResponse> responses(Map<String, Marker> found) {
        return found.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> MarkerResponse.from(entry.getValue())));
    }

    private record MarkerBody(Position home, Position notifications) {
    }

    private record Position(@JsonProperty("last_read_id") String lastReadId) {
    }
}
