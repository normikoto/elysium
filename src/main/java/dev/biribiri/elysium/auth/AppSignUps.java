package dev.biribiri.elysium.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Set;

/**
 * Signs people up from an app, through Mastodon's {@code POST /api/v1/accounts}. It follows the sign-up page's rules, and
 * answers with a token for the new account, which only starts working once the account exists: see
 * {@code SignUpTokens}.
 */
public interface AppSignUps {

    /**
     * @param applicationId the application whose token made the request, which gets the new account's token
     * @throws SignUpRefusedException     if sign-ups without an invite are closed, or no account exists yet
     * @throws InvalidSignUpException     if a field is invalid, the username or address is taken, or the invite doesn't
     *                                    work
     * @throws SignUpLimitException       if the request's address has signed up too often
     * @throws SignUpUnavailableException if the server is too busy, or couldn't send the email to confirm the address
     */
    SignedUp signUp(HttpServletRequest request, String applicationId, Form form);

    /**
     * What the person entered. Any of it can be null.
     *
     * @param agreement whether they accepted the server's rules
     */
    record Form(
            String username,
            String email,
            String password,
            boolean agreement,
            String reason,
            String inviteCode) {

        @Override
        public String toString() {
            return "Form[username=" + username + "]";
        }
    }

    /**
     * The new account's token.
     */
    record SignedUp(String accessToken, Set<String> scopes, Instant createdAt) {

        @Override
        public String toString() {
            return "SignedUp[scopes=" + scopes + "]";
        }
    }
}
