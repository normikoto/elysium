package dev.biribiri.elysium.api.web;

/**
 * Thrown when the user can see what a request names, but isn't the one who can act on it, which Mastodon answers with a
 * 403.
 */
class ForbiddenActionException extends RuntimeException {
}
