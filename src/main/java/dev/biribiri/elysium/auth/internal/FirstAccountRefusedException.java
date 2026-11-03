package dev.biribiri.elysium.auth.internal;

/**
 * The sign-up page tried to create the server's first account. That account becomes an admin, so it has to come from the
 * {@code create-user} command. Otherwise the first stranger to sign up could take over the server.
 */
class FirstAccountRefusedException extends RuntimeException {
}
