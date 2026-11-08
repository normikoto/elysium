package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.inbox.Conversation;
import dev.biribiri.elysium.inbox.InboxService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "conversations/"))
@ApiResponse(responseCode = "422", description = "The token has no user, or a cursor or limit is invalid.")
public class ConversationsController {
    private final InboxService inbox;
    private final InboxResponses responses;
    private final InstanceProperties instance;

    ConversationsController(InboxService inbox, InboxResponses responses, InstanceProperties instance) {
        this.inbox = inbox;
        this.responses = responses;
        this.instance = instance;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_read:statuses')")
    @Operation(operationId = "getConversations", summary = "List direct-message conversations",
            description = """
                    Conversations are grouped by thread and participant set. Use Link headers for pagination: cursors \
                    are latest status IDs, not conversation IDs. Existing messages are backfilled as read. Only stored \
                    statuses are included; missing remote parents are never fetched.\n\n""" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "conversations/#get"),
            parameters = {
                    @Parameter(in = ParameterIn.QUERY, name = "limit", description = "Page size, clamped to 1–40.",
                            schema = @Schema(type = "integer", defaultValue = "20")),
                    @Parameter(in = ParameterIn.QUERY, name = "max_id", description = "Internal cursor for older conversations; use the Link header."),
                    @Parameter(in = ParameterIn.QUERY, name = "since_id", description = "Internal cursor for newer conversations; use the Link header."),
                    @Parameter(in = ParameterIn.QUERY, name = "min_id", description = "Internal cursor for the closest newer conversations; use the Link header.")
            }, responses = @ApiResponse(responseCode = "200", description = "Conversations, most recently active first."))
    public ResponseEntity<List<ConversationResponse>> list(@AuthenticationPrincipal TokenPrincipal token,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        var viewer = NotificationRequests.viewer(token);
        var page = NotificationRequests.conversationPage(query);
        var found = inbox.conversations(viewer, page);
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, "/api/v1/conversations", Map.of(), page.limit(),
                found.stream().map(Conversation::lastStatusId).toList());
        return response.body(responses.conversations(viewer, found));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_write:conversations')")
    @Operation(operationId = "deleteConversation", summary = "Hide a conversation",
            description = "Only hides it for this user. A newer message makes it appear again.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "conversations/#delete"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The conversation is hidden; returns an empty object."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")
            })
    public Map<String, Object> delete(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        if (!inbox.hideConversation(NotificationRequests.viewer(token), NotificationRequests.id(id))) {
            throw new RecordNotFoundException();
        }
        return Map.of();
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('SCOPE_write:conversations')")
    @Operation(operationId = "readConversation", summary = "Mark a conversation as read",
            description = "Does not change notification read markers.\n\n" + ApiDocs.NEEDS_USER,
            externalDocs = @ExternalDocumentation(url = ApiDocs.MASTODON_DOCS + "conversations/#read"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "The conversation with unread set to false."),
                    @ApiResponse(responseCode = "404", description = "Not found or not owned by the user.")
            })
    public ConversationResponse read(@AuthenticationPrincipal TokenPrincipal token, @PathVariable String id) {
        var viewer = NotificationRequests.viewer(token);
        var found = inbox.readConversation(viewer, NotificationRequests.id(id)).orElseThrow(RecordNotFoundException::new);
        return responses.conversations(viewer, List.of(found)).stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }
}
