package dev.biribiri.elysium.api.web;

/**
 * Thrown when a request names something that doesn't exist or that the caller isn't allowed to see. These look the same
 * to the client, so that a response never confirms that a private status exists.
 */
class RecordNotFoundException extends RuntimeException {
}
