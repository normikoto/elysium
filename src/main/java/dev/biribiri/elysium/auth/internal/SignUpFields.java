package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;

import java.util.ArrayList;
import java.util.List;

/**
 * The checks a sign-up's fields have to pass, which the sign-up page and {@code POST /api/v1/accounts} both make, so that
 * one can't accept what the other refuses. Each says what's wrong in its own words.
 */
final class SignUpFields {

    private SignUpFields() {
    }

    /**
     * @param invited whether an invite is used, in which case there's no address to confirm
     * @return what's wrong, in the order the fields are shown, or nothing
     */
    static List<Problem> check(String username, String password, boolean invited, String email, String reason) {
        var problems = new ArrayList<Problem>();
        if (username == null || username.isBlank()) {
            problems.add(Problem.USERNAME_BLANK);
        } else if (!Actor.isValidLocalUsername(username)) {
            problems.add(Problem.USERNAME_INVALID);
        }
        if (password == null || password.isEmpty()) {
            problems.add(Problem.PASSWORD_BLANK);
        } else if (password.length() < LocalAccounts.MIN_PASSWORD_LENGTH) {
            problems.add(Problem.PASSWORD_TOO_SHORT);
        } else if (password.length() > LocalAccounts.MAX_PASSWORD_LENGTH) {
            problems.add(Problem.PASSWORD_TOO_LONG);
        }
        if (!invited) {
            if (email == null || email.isBlank()) {
                problems.add(Problem.EMAIL_BLANK);
            } else if (!SignUps.isValidEmail(email.strip())) {
                problems.add(Problem.EMAIL_INVALID);
            }
        }
        if (reason != null && reason.length() > SignUps.MAX_REASON_LENGTH) {
            problems.add(Problem.REASON_TOO_LONG);
        }
        return problems;
    }

    enum Problem {
        USERNAME_BLANK, USERNAME_INVALID,
        PASSWORD_BLANK, PASSWORD_TOO_SHORT, PASSWORD_TOO_LONG,
        EMAIL_BLANK, EMAIL_INVALID,
        REASON_TOO_LONG
    }
}
