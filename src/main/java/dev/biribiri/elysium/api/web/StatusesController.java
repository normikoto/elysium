package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/statuses")
public class StatusesController {

    private final StatusService statuses;
    private final StatusResponses responses;

    StatusesController(StatusService statuses, StatusResponses responses) {
        this.statuses = statuses;
        this.responses = responses;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse createFromForm(
            @AuthenticationPrincipal TokenPrincipal token, @RequestParam MultiValueMap<String, String> form) {
        return create(token, Map.copyOf(form));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_write:statuses')")
    public StatusResponse createFromJson(
            @AuthenticationPrincipal TokenPrincipal token, @RequestBody Map<String, Object> body) {
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
        rejectUnsupported(parameters);

        var visibility = string(parameters.get("visibility"));
        var draft = new NewStatus(
                string(parameters.get("status")),
                string(parameters.get("spoiler_text")),
                string(parameters.get("language")),
                visibility == null ? Visibility.PUBLIC : Visibility.fromValue(visibility),
                flag(parameters.get("sensitive")),
                replyParent(string(parameters.get("in_reply_to_id"))));
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
     */
    private static void rejectUnsupported(Map<String, ?> parameters) {
        parameters.forEach((name, value) -> {
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
     * Reads a value from either request format: forms have a list of strings for each name, and JSON has a single value.
     * An empty value counts as missing, because form clients send fields they haven't filled in.
     */
    private static String string(Object value) {
        return switch (value) {
            case String string when !string.isEmpty() -> string;
            case List<?> values when !values.isEmpty() -> string(values.getFirst());
            case null, default -> null;
        };
    }

    private static boolean flag(Object value) {
        return value instanceof Boolean flag ? flag : RequestParameters.flag(string(value));
    }
}
