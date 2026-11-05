package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statuses")
public class StatusesController {

    /**
     * The most a character can take up in JSON, as an escaped surrogate pair like {@code \ud83d\ude00}.
     */
    private static final long MAX_BYTES_PER_CHARACTER = 12;

    /**
     * Room for the JSON syntax and the fields that don't count toward the length limit.
     */
    private static final long OTHER_FIELDS_BYTES = 64 * 1024;

    private final StatusService statuses;
    private final StatusResponses responses;
    private final JsonBodies json;

    StatusesController(StatusService statuses, StatusResponses responses, JsonBodies json) {
        this.statuses = statuses;
        this.responses = responses;
        this.json = json;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        rejectUnsupported(form);
        return create(token, form.toSingleValueMap());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    // The API docs combine both mappings into one operation, and this replaces the body documented by the other one.
    @RequestBody(required = true, content = {
            @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE, schema = @Schema(type = "object")),
            @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(type = "object"))
    })
    public StatusResponse createFromJson(@AuthenticationPrincipal TokenPrincipal token, HttpServletRequest request)
            throws IOException {
        var body = json.read(request, statuses.maxStoredCharacters() * MAX_BYTES_PER_CHARACTER + OTHER_FIELDS_BYTES);
        rejectUnsupported(body);
        return create(token, body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAnonymous() or hasAuthority('SCOPE_read:statuses')")
    public StatusResponse get(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.findVisible(statusId, RequestParameters.viewer(token)))
                .map(responses::one)
                .orElseThrow(RecordNotFoundException::new);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse delete(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var author = requireUser(token);
        return RequestParameters.uuid(id)
                .flatMap(statusId -> statuses.delete(statusId, author))
                .map(responses::oneWithText)
                .orElseThrow(RecordNotFoundException::new);
    }

    private StatusResponse create(TokenPrincipal token, Map<String, ?> parameters) {
        var author = requireUser(token);
        var draft = new NewStatus(
                RequestParameters.string(parameters, "status"),
                RequestParameters.string(parameters, "spoiler_text"),
                RequestParameters.string(parameters, "language"),
                visibility(parameters),
                flag(parameters, "sensitive"),
                replyParent(RequestParameters.string(parameters, "in_reply_to_id")));
        return responses.one(statuses.create(author, draft));
    }

    private static UUID requireUser(TokenPrincipal token) {
        return token.actorId().orElseThrow(InvalidRequestException::requiresUser);
    }

    private static UUID replyParent(String id) {
        return id == null ? null : RequestParameters.uuid(id).orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Ignoring these would do the wrong thing without telling anyone. For example, a scheduled status would be published
     * right away. Clients can still send them empty.
     *
     * <p>A visibility that can't be read as one value is also rejected here, because reading only part of it could make
     * a status public.
     */
    private static void rejectUnsupported(Map<String, ?> parameters) {
        parameters.forEach((name, value) -> {
            var repeated = name.equals("visibility") && value instanceof Collection<?> values && values.size() > 1;
            if (repeated || name.startsWith("visibility[")) {
                throw new InvalidRequestException("Validation failed: visibility must be a single value");
            }
            var unsupported = name.equals("media_ids") || name.equals("media_ids[]") || name.equals("scheduled_at")
                    || name.equals("poll") || name.startsWith("poll[");
            if (unsupported && isPresent(value)) {
                throw new InvalidRequestException("Validation failed: " + name + " is not supported");
            }
        });
    }

    private static boolean isPresent(Object value) {
        return switch (value) {
            case null -> false;
            case String string -> !string.isEmpty();
            case Collection<?> values -> values.stream().anyMatch(StatusesController::isPresent);
            case Map<?, ?> values -> !values.isEmpty();
            default -> true;
        };
    }

    /**
     * Only a missing visibility means public. An empty one is rejected, so that a client's mistake can't publish a status
     * that was meant to be private.
     */
    private static Visibility visibility(Map<String, ?> parameters) {
        return switch (parameters.get("visibility")) {
            case null -> Visibility.PUBLIC;
            case String value -> Visibility.fromValue(value);
            default -> throw new InvalidRequestException("Validation failed: visibility must be a string");
        };
    }

    private static boolean flag(Map<String, ?> parameters, String name) {
        return switch (parameters.get(name)) {
            case null -> false;
            case Boolean flag -> flag;
            case String string -> RequestParameters.flag(string);
            default -> throw new InvalidRequestException("Validation failed: " + name + " must be a boolean");
        };
    }
}
