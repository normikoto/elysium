package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Ids;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * The admin page for approving sign-ups. Everything under {@code /admin} is limited to admins and needs a CSRF token, through
 * the security configuration.
 */
@Controller
class AdminSignUpPagesController {

    private static final String PATH = "/admin/sign-ups";

    private final SignUps signUps;

    AdminSignUpPagesController(SignUps signUps) {
        this.signUps = signUps;
    }

    @GetMapping(PATH)
    String list(Model model) {
        model.addAttribute("signUps", signUps.awaitingApproval());
        return "admin-sign-ups";
    }

    @PostMapping(PATH + "/{id}/approve")
    String approve(@PathVariable String id, Model model, HttpServletResponse response) {
        boolean approved;
        try {
            approved = signUps.approve(parse(id));
        } catch (FirstAccountRefusedException e) {
            approved = false;
        }
        return answered(approved, model, response);
    }

    @PostMapping(PATH + "/{id}/reject")
    String reject(@PathVariable String id, Model model, HttpServletResponse response) {
        return answered(signUps.reject(parse(id)), model, response);
    }

    /**
     * Another admin may have answered first, which only needs saying, since the list shows what's left.
     */
    private String answered(boolean done, Model model, HttpServletResponse response) {
        if (done) {
            return "redirect:" + PATH;
        }
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("error", "That sign-up isn't waiting any more.");
        return list(model);
    }

    private static UUID parse(String id) {
        return Ids.parse(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
