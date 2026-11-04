package dev.biribiri.elysium.activitypub.http;

import java.io.IOException;

/**
 * A response body was over the limit, so it was abandoned without reading the rest.
 */
public class ResponseTooLargeException extends IOException {

    ResponseTooLargeException(String message) {
        super(message);
    }
}
