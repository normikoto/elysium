package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.InvalidStatusException;
import dev.biribiri.elysium.content.ReplyParentNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Handles only these specific exceptions. Catching anything broader would stop scope denials from reaching Spring
 * Security, which turns them into the API's 403 response.
 */
@RestControllerAdvice(assignableTypes = {AccountsController.class, StatusesController.class, TimelinesController.class})
class StatusApiErrors {

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidStatus(InvalidStatusException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("error", "Validation failed: " + e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<Map<String, String>> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({RecordNotFoundException.class, ReplyParentNotFoundException.class})
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Record not found"));
    }
}
