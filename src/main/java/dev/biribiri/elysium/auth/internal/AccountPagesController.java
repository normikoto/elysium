package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.RegistrationProperties;
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
    private final RegistrationProperties registration;
    private final AuthRateLimits limits;

    AccountPagesController(LocalAccounts accounts, RegistrationProperties registration, AuthRateLimits limits) {
        this.accounts = accounts;
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
        model.addAttribute("registrationEnabled", registration.enabled());
        return "login";
    }

    @GetMapping(SecurityConfig.SIGNUP_PATH)
    String signupForm(CsrfToken csrf, Model model, HttpServletResponse response) {
        requireRegistrationEnabled();
        if (accounts.hasNoAccounts()) {
            return signupUnavailable(response);
        }
        addCsrf(model, csrf);
        model.addAttribute("username", "");
        return "signup";
    }

    @PostMapping(SecurityConfig.SIGNUP_PATH)
    String signup(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            CsrfToken csrf,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        requireRegistrationEnabled();
        if (accounts.hasNoAccounts()) {
            return signupUnavailable(response);
        }

        var status = HttpStatus.UNPROCESSABLE_CONTENT;
        var error = validate(username, password, confirmPassword);
        if (error == null) {
            // This comes after validating, so that a mistyped password doesn't use up an attempt.
            var wait = limits.beginSignUp(request);
            if (wait.isPresent()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(wait.get()));
                status = HttpStatus.TOO_MANY_REQUESTS;
                error = "Too many sign-ups from your network. Try again later.";
            } else {
                try {
                    if (!accounts.signUp(username, password)) {
                        error = "That username is taken.";
                    }
                } catch (FirstAccountRefusedException e) {
                    limits.signUpNotCounted(request);
                    return signupUnavailable(response);
                } catch (PasswordHashingBusyException e) {
                    limits.signUpNotCounted(request);
                    status = HttpStatus.SERVICE_UNAVAILABLE;
                    error = "The server is busy. Try again in a moment.";
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
        addCsrf(model, csrf);
        model.addAttribute("username", username);
        model.addAttribute("error", error);
        return "signup";
    }

    private static String validate(String username, String password, String confirmPassword) {
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
        return null;
    }

    /**
     * The first account has to come from the {@code create-user} command: see {@link FirstAccountRefusedException}.
     */
    private static String signupUnavailable(HttpServletResponse response) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return "signup-unavailable";
    }

    private void requireRegistrationEnabled() {
        if (!registration.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void addCsrf(Model model, CsrfToken csrf) {
        model.addAttribute("csrfParameter", csrf.getParameterName());
        model.addAttribute("csrfToken", csrf.getToken());
    }
}
