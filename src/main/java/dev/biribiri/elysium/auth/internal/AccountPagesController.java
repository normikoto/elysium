package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.mail.MailNotSentException;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * The login and sign-up pages. Spring Security handles the login form's submission itself.
 */
@Controller
class AccountPagesController {

    private final LocalAccounts accounts;
    private final SignUps signUps;
    private final Invites invites;
    private final RegistrationProperties registration;
    private final AuthRateLimits limits;

    AccountPagesController(
            LocalAccounts accounts,
            SignUps signUps,
            Invites invites,
            RegistrationProperties registration,
            AuthRateLimits limits) {
        this.accounts = accounts;
        this.signUps = signUps;
        this.invites = invites;
        this.registration = registration;
        this.limits = limits;
    }

    @GetMapping(SecurityConfig.LOGIN_PATH)
    String login(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            @RequestParam(required = false) String registered,
            @RequestParam(required = false) String busy,
            @RequestParam(required = false) String deleted,
            CsrfToken csrf,
            Model model) {
        addCsrf(model, csrf);
        model.addAttribute("failed", error != null);
        model.addAttribute("busy", busy != null);
        model.addAttribute("loggedOut", logout != null);
        model.addAttribute("registered", registered != null);
        model.addAttribute("deleted", deleted != null);
        model.addAttribute("registrationEnabled", registration.acceptsUninvited());
        return "login";
    }

    /**
     * Asks for a code, once a password was right for an account with two-factor login: see {@link TwoFactorLoginFilter}.
     */
    @GetMapping(SecurityConfig.TWO_FACTOR_LOGIN_PATH)
    String twoFactorLogin(
            @RequestParam(required = false) String error,
            CsrfToken csrf,
            Model model,
            HttpServletRequest request) {
        if (TwoFactorChallenge.current(request).isEmpty()) {
            return "redirect:" + SecurityConfig.LOGIN_PATH;
        }
        addCsrf(model, csrf);
        model.addAttribute("failed", error != null);
        model.addAttribute("codeParameter", TwoFactorLoginFilter.CODE_PARAMETER);
        return "login-two-factor";
    }

    @GetMapping(SecurityConfig.SIGNUP_PATH)
    String signupForm(
            @RequestParam(required = false) String invite,
            CsrfToken csrf,
            Model model,
            HttpServletResponse response) {
        var invited = isPresent(invite);
        if (!invited) {
            requireUninvitedSignUps();
        }
        if (accounts.hasNoAccounts()) {
            return signupUnavailable(response);
        }
        if (invited && !invites.isUsable(invite)) {
            return inviteUnusable(response);
        }
        return form(model, csrf, invited ? invite : null, "", "", "");
    }

    @PostMapping(SecurityConfig.SIGNUP_PATH)
    String signup(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String invite,
            CsrfToken csrf,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var invited = isPresent(invite);
        if (!invited) {
            requireUninvitedSignUps();
        }
        if (accounts.hasNoAccounts()) {
            return signupUnavailable(response);
        }

        var status = HttpStatus.UNPROCESSABLE_CONTENT;
        var error = validate(username, password, confirmPassword, invited, email, reason);
        if (error == null) {
            // This comes after validating, so that a mistyped password doesn't use up an attempt.
            var wait = limits.beginSignUp(request);
            if (wait.isPresent()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
                status = HttpStatus.TOO_MANY_REQUESTS;
                error = "Too many sign-ups from your network. Try again later.";
            } else {
                try {
                    if (invited) {
                        if (!signUps.signUpWithInvite(invite, username, password)) {
                            error = "That username is taken.";
                        }
                    } else {
                        switch (signUps.request(username, email.strip(), password, reason)) {
                            case SENT -> {
                                model.addAttribute("email", email.strip());
                                return "signup-sent";
                            }
                            case USERNAME_TAKEN -> error = "That username is taken.";
                            case EMAIL_TAKEN -> error = "That email address is already in use.";
                        }
                    }
                } catch (InviteUnusableException e) {
                    return inviteUnusable(response);
                } catch (FirstAccountRefusedException e) {
                    limits.signUpNotCounted(request);
                    return signupUnavailable(response);
                } catch (PasswordHashingBusyException e) {
                    limits.signUpNotCounted(request);
                    status = HttpStatus.SERVICE_UNAVAILABLE;
                    error = "The server is busy. Try again in a moment.";
                } catch (MailNotSentException e) {
                    limits.signUpNotCounted(request);
                    status = HttpStatus.SERVICE_UNAVAILABLE;
                    error = "The server couldn't send the email to confirm your address. Try again later.";
                } catch (RuntimeException e) {
                    // Such as the database being down, which shouldn't use up the attempts of people retrying.
                    limits.signUpNotCounted(request);
                    throw e;
                }
            }
        }
        if (error == null) {
            return "redirect:" + SecurityConfig.LOGIN_PATH + "?registered";
        }

        response.setStatus(status.value());
        model.addAttribute("error", error);
        return form(model, csrf, invited ? invite : null, username, email, reason);
    }

    /**
     * Only shows what the link is for, because mail scanners open links, and that mustn't confirm anything.
     */
    @GetMapping(SecurityConfig.SIGNUP_VERIFY_PATH)
    String confirmForm(
            @RequestParam(required = false) String token,
            CsrfToken csrf,
            Model model,
            HttpServletResponse response) {
        requireUninvitedSignUps();
        var pending = signUps.findUnconfirmed(token);
        if (pending.isEmpty()) {
            return linkExpired(response);
        }
        addCsrf(model, csrf);
        model.addAttribute("token", token);
        model.addAttribute("username", pending.get().username());
        model.addAttribute("approvalRequired", registration.approvalRequired());
        return "signup-confirm";
    }

    @PostMapping(SecurityConfig.SIGNUP_VERIFY_PATH)
    String confirm(@RequestParam(required = false) String token, HttpServletResponse response) {
        requireUninvitedSignUps();
        try {
            return switch (signUps.confirm(token)) {
                case CREATED -> "redirect:" + SecurityConfig.LOGIN_PATH + "?registered";
                case AWAITING_APPROVAL -> "signup-awaiting-approval";
                case NOT_FOUND -> linkExpired(response);
            };
        } catch (FirstAccountRefusedException e) {
            return signupUnavailable(response);
        }
    }

    private String form(Model model, CsrfToken csrf, String invite, String username, String email, String reason) {
        addCsrf(model, csrf);
        model.addAttribute("invite", invite);
        model.addAttribute("username", username);
        model.addAttribute("email", email == null ? "" : email);
        model.addAttribute("reason", reason == null ? "" : reason);
        model.addAttribute("askForReason", invite == null && registration.approvalRequired());
        model.addAttribute("maxReasonLength", SignUps.MAX_REASON_LENGTH);
        return "signup";
    }

    private static String validate(
            String username, String password, String confirmPassword, boolean invited, String email, String reason) {
        if (!Actor.isValidLocalUsername(username)) {
            return "Usernames must be 1 to 30 letters, digits, or underscores.";
        }
        if (!LocalAccounts.isValidPassword(password)) {
            return "Passwords must be %d to %d characters."
                    .formatted(LocalAccounts.MIN_PASSWORD_LENGTH, LocalAccounts.MAX_PASSWORD_LENGTH);
        }
        if (!password.equals(confirmPassword)) {
            return "The passwords don't match.";
        }
        if (!invited && !SignUps.isValidEmail(email == null ? null : email.strip())) {
            return "Enter a valid email address.";
        }
        if (reason != null && reason.length() > SignUps.MAX_REASON_LENGTH) {
            return "Keep the reason to %d characters.".formatted(SignUps.MAX_REASON_LENGTH);
        }
        return null;
    }

    /**
     * The first account has to come from the {@code create-user} command: see {@link FirstAccountRefusedException}.
     */
    private static String signupUnavailable(HttpServletResponse response) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return "signup-unavailable";
    }

    private static String inviteUnusable(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "signup-invite-unusable";
    }

    private static String linkExpired(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "signup-link-expired";
    }

    private void requireUninvitedSignUps() {
        if (!registration.acceptsUninvited()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static void addCsrf(Model model, CsrfToken csrf) {
        model.addAttribute("csrfParameter", csrf.getParameterName());
        model.addAttribute("csrfToken", csrf.getToken());
    }
}
