package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.auth.AppSignUps;
import dev.biribiri.elysium.auth.InvalidSignUpException;
import dev.biribiri.elysium.auth.InvalidSignUpException.Problem;
import dev.biribiri.elysium.auth.SignUpLimitException;
import dev.biribiri.elysium.auth.SignUpRefusedException;
import dev.biribiri.elysium.auth.SignUpUnavailableException;
import dev.biribiri.elysium.mail.MailNotSentException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes sign-ups from apps as the sign-up page makes them: with an invite, the account is created straight away, and the
 * address isn't asked for, since nothing would confirm it. Without one, the sign-up waits for its address to be confirmed,
 * and for an admin if the registration mode asks for that.
 */
@Service
class SignUpsFromApps implements AppSignUps {

    private final LocalAccounts accounts;
    private final SignUps signUps;
    private final SignUpTokens tokens;
    private final RegistrationProperties registration;
    private final AuthRateLimits limits;

    SignUpsFromApps(
            LocalAccounts accounts,
            SignUps signUps,
            SignUpTokens tokens,
            RegistrationProperties registration,
            AuthRateLimits limits) {
        this.accounts = accounts;
        this.signUps = signUps;
        this.tokens = tokens;
        this.registration = registration;
        this.limits = limits;
    }

    @Override
    public SignedUp signUp(HttpServletRequest request, String applicationId, Form form) {
        var invited = form.inviteCode() != null && !form.inviteCode().isBlank();
        if ((!invited && !registration.acceptsUninvited()) || accounts.hasNoAccounts()) {
            throw new SignUpRefusedException();
        }
        var invalid = check(form, invited);
        if (!invalid.isEmpty()) {
            throw new InvalidSignUpException(invalid);
        }

        // As on the page, this comes after validating, so that a typing mistake doesn't use up an attempt. A taken
        // username does use one, which limits using sign-ups to find out which ones exist.
        var wait = limits.beginSignUp(request);
        if (wait.isPresent()) {
            throw new SignUpLimitException(wait.get());
        }
        var id = UUID.randomUUID();
        InvalidSignUpException taken = null;
        try {
            if (invited) {
                if (!signUps.signUpWithInvite(id, form.inviteCode(), form.username(), form.password())) {
                    taken = InvalidSignUpException.of("username", Problem.TAKEN);
                }
            } else {
                taken = switch (signUps.request(id, form.username(), form.email().strip(), form.password(), form.reason())) {
                    case SENT -> null;
                    case USERNAME_TAKEN -> InvalidSignUpException.of("username", Problem.TAKEN);
                    case EMAIL_TAKEN -> InvalidSignUpException.of("email", Problem.TAKEN);
                };
            }
        } catch (InviteUnusableException e) {
            throw InvalidSignUpException.of("invite_code", Problem.INVALID);
        } catch (FirstAccountRefusedException e) {
            limits.signUpNotCounted(request);
            throw new SignUpRefusedException();
        } catch (PasswordHashingBusyException e) {
            limits.signUpNotCounted(request);
            throw new SignUpUnavailableException("The server is busy. Try again in a moment.");
        } catch (MailNotSentException e) {
            limits.signUpNotCounted(request);
            throw new SignUpUnavailableException(
                    "The server couldn't send the email to confirm your address. Try again later.");
        } catch (RuntimeException e) {
            // Such as the database being down, which shouldn't use up the attempts of people retrying.
            limits.signUpNotCounted(request);
            throw e;
        }
        if (taken != null) {
            throw taken;
        }

        // The account or sign-up has already been committed, so if this fails, it's still there, and they can log in
        // through OAuth once it's ready, as with a sign-up from the page.
        var token = tokens.issue(applicationId, id);
        return new SignedUp(token.getTokenValue(), token.getScopes(), token.getIssuedAt());
    }

    private static Map<String, List<Problem>> check(Form form, boolean invited) {
        var details = new LinkedHashMap<String, List<Problem>>();
        for (var problem : SignUpFields.check(form.username(), form.password(), invited, form.email(), form.reason())) {
            switch (problem) {
                case USERNAME_BLANK -> details.put("username", List.of(Problem.BLANK));
                case USERNAME_INVALID -> details.put("username", List.of(new Problem("ERR_INVALID",
                        "must contain only letters, numbers and underscores, and be at most 30 characters")));
                case PASSWORD_BLANK -> details.put("password", List.of(Problem.BLANK));
                case PASSWORD_TOO_SHORT -> details.put("password",
                        List.of(Problem.tooShort(LocalAccounts.MIN_PASSWORD_LENGTH)));
                case PASSWORD_TOO_LONG -> details.put("password",
                        List.of(Problem.tooLong(LocalAccounts.MAX_PASSWORD_LENGTH)));
                case EMAIL_BLANK -> details.put("email", List.of(Problem.BLANK));
                case EMAIL_INVALID -> details.put("email", List.of(Problem.INVALID));
                case REASON_TOO_LONG -> details.put("reason", List.of(Problem.tooLong(SignUps.MAX_REASON_LENGTH)));
            }
        }
        if (!form.agreement()) {
            details.put("agreement", List.of(Problem.NOT_ACCEPTED));
        }
        return details;
    }
}
