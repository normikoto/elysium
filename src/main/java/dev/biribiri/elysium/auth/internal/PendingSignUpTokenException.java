package dev.biribiri.elysium.auth.internal;

import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;

/**
 * A token issued by {@code POST /api/v1/accounts} for an account that's still a pending sign-up. Mastodon answers 403 with
 * these messages, and apps that signed someone up show a waiting screen while they get it, rather than logging out as they
 * would on a 401.
 */
class PendingSignUpTokenException extends BadOpaqueTokenException {

    static final String UNCONFIRMED = "Your login is missing a confirmed e-mail address";
    static final String AWAITING_APPROVAL = "Your login is currently pending approval";

    PendingSignUpTokenException(SignUps.Waiting waiting) {
        super(waiting == SignUps.Waiting.FOR_APPROVAL ? AWAITING_APPROVAL : UNCONFIRMED);
    }
}
