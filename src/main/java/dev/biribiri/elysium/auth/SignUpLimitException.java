package dev.biribiri.elysium.auth;

import dev.biribiri.elysium.ratelimit.RateLimiter;

import java.time.Duration;

/**
 * Too many sign-ups came from the client's address.
 */
public class SignUpLimitException extends RuntimeException {

    private final Duration retryAfter;

    public SignUpLimitException(Duration retryAfter) {
        super("Too many requests");
        this.retryAfter = retryAfter;
    }

    /**
     * The value for the response's {@code Retry-After} header. It's worked out here because callers outside this module
     * can't use {@code ratelimit}.
     */
    public String retryAfterSeconds() {
        return RateLimiter.retryAfterSeconds(retryAfter);
    }
}
