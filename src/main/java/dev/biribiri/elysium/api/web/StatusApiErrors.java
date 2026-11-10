package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InvalidProfileException;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.InvalidMediaAttachmentException;
import dev.biribiri.elysium.content.InvalidStatusException;
import dev.biribiri.elysium.inbox.MarkerConflictException;
import dev.biribiri.elysium.content.ReplyParentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Handles only these specific exceptions. Catching anything broader would stop scope denials from reaching Spring
 * Security, which turns them into the API's 403 response.
 */
@RestControllerAdvice(assignableTypes = {
        AccountsController.class,
        FollowRequestsController.class,
        NotificationsController.class,
        GroupedNotificationsController.class,
        ConversationsController.class,
        MarkersController.class,
        MediaController.class,
        AdminAccountsController.class,
        DomainBlocksController.class,
        ProfileController.class,
        SearchController.class,
        StatusesController.class,
        TagsController.class,
        TimelinesController.class})
class StatusApiErrors {

    @ExceptionHandler
    ResponseEntity<Map<String, String>> markerConflict(MarkerConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidStatus(InvalidStatusException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Validation failed: " + e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidMedia(InvalidMediaAttachmentException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Validation failed: " + e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidProfile(InvalidProfileException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({RequestTooLargeException.class, MaxUploadSizeExceededException.class})
    ResponseEntity<Map<String, String>> requestTooLarge() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("error", "Request body is too large"));
    }

    @ExceptionHandler(RemoteActorResolver.ResolveLimitException.class)
    ResponseEntity<Map<String, String>> tooManyLookups(RemoteActorResolver.ResolveLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({RecordNotFoundException.class, ReplyParentNotFoundException.class})
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Record not found"));
    }
}
