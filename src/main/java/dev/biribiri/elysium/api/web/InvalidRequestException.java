package dev.biribiri.elysium.api.web;

/**
 * Thrown when a request can't be processed. The message is sent to the client as is.
 */
class InvalidRequestException extends RuntimeException {

    InvalidRequestException(String message) {
        super(message);
    }

    static InvalidRequestException requiresUser() {
        return new InvalidRequestException("This method requires an authenticated user");
    }
}
