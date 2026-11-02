package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean registrationEnabled;

    AccountPagesController(
            LocalAccounts accounts,
            @Value("${elysium.registration.enabled:false}") boolean registrationEnabled) {
        this.accounts = accounts;
        this.registrationEnabled = registrationEnabled;
    }

    @GetMapping(SecurityConfig.LOGIN_PATH)
    String login(
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout,
            @RequestParam(required = false) String registered,
            @RequestParam(required = false) String busy,
            CsrfToken csrf,
            Model model) {
        addCsrf(model, csrf);
        model.addAttribute("failed", error != null);
        model.addAttribute("busy", busy != null);
        model.addAttribute("loggedOut", logout != null);
        model.addAttribute("registered", registered != null);
        model.addAttribute("registrationEnabled", registrationEnabled);
        return "login";
    }

    @GetMapping(SecurityConfig.SIGNUP_PATH)
    String signupForm(CsrfToken csrf, Model model) {
        requireRegistrationEnabled();
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
            HttpServletResponse response) {
        requireRegistrationEnabled();

        var status = HttpStatus.UNPROCESSABLE_CONTENT;
        var error = validate(username, password, confirmPassword);
        if (error == null) {
            try {
                if (!accounts.signUp(username, password)) {
                    error = "That username is taken.";
                }
            } catch (PasswordHashingBusyException e) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                error = "The server is busy. Try again in a moment.";
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

    private void requireRegistrationEnabled() {
        if (!registrationEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void addCsrf(Model model, CsrfToken csrf) {
        model.addAttribute("csrfParameter", csrf.getParameterName());
        model.addAttribute("csrfToken", csrf.getToken());
    }
}
