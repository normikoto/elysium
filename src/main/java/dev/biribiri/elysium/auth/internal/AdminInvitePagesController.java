package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Ids;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The admin page for invites, which does what the invite commands do. Everything under {@code /admin} is limited to admins
 * and needs a CSRF token, through the security configuration.
 */
@Controller
class AdminInvitePagesController {

    private static final String PATH = "/admin/invites";

    /**
     * A fixed list rather than a free duration, so that the form can't be given one it can't show back.
     */
    private static final Map<String, Duration> EXPIRY_CHOICES = expiryChoices();

    private final Invites invites;

    AdminInvitePagesController(Invites invites) {
        this.invites = invites;
    }

    @GetMapping(PATH)
    String list(Model model) {
        var now = Instant.now();
        model.addAttribute("invites", invites.list().stream().map(invite -> Row.from(invite, now)).toList());
        model.addAttribute("expiryChoices", EXPIRY_CHOICES.keySet());
        return "admin-invites";
    }

    /**
     * Shows the new link in the response rather than redirecting, since it's the only copy there is: carrying it across a
     * redirect would mean storing it in the session, which is in the database.
     */
    @PostMapping(PATH)
    String create(
            @RequestParam(name = "max_uses", required = false) String maxUses,
            @RequestParam(name = "expires_in", required = false) String expiresIn,
            Model model,
            HttpServletResponse response) {
        Integer uses = null;
        if (maxUses != null && !maxUses.isBlank()) {
            try {
                uses = Integer.valueOf(maxUses.strip());
            } catch (NumberFormatException e) {
                uses = 0;
            }
            if (uses < 1) {
                return refused("The number of uses has to be a whole number of at least 1.", model, response);
            }
        }
        Instant expiresAt = null;
        if (expiresIn != null && !expiresIn.isBlank()) {
            var duration = EXPIRY_CHOICES.get(expiresIn);
            if (duration == null) {
                return refused("Pick how long the invite works for from the list.", model, response);
            }
            expiresAt = Instant.now().plus(duration);
        }
        model.addAttribute("created", invites.create(uses, expiresAt).url());
        return list(model);
    }

    /**
     * Revoking one that's already been revoked is the outcome that was asked for, so it isn't an error.
     */
    @PostMapping(PATH + "/{id}/revoke")
    String revoke(@PathVariable String id) {
        Ids.parse(id).ifPresent(invites::revoke);
        return "redirect:" + PATH;
    }

    private String refused(String error, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("error", error);
        return list(model);
    }

    private static Map<String, Duration> expiryChoices() {
        var choices = new LinkedHashMap<String, Duration>();
        choices.put("30 minutes", Duration.ofMinutes(30));
        choices.put("1 hour", Duration.ofHours(1));
        choices.put("12 hours", Duration.ofHours(12));
        choices.put("1 day", Duration.ofDays(1));
        choices.put("7 days", Duration.ofDays(7));
        choices.put("30 days", Duration.ofDays(30));
        return choices;
    }

    record Row(UUID id, String uses, String state, boolean usable, String createdAt) {

        static Row from(Invites.Invite invite, Instant now) {
            var unusable = invite.unusableBecause(now);
            var state = unusable.map(reason -> reason.substring(0, 1).toUpperCase() + reason.substring(1))
                    .orElse(invite.expiresAt() == null ? "Never expires" : "Expires " + seconds(invite.expiresAt()));
            return new Row(invite.id(), invite.usesText(), state, unusable.isEmpty(), seconds(invite.createdAt()));
        }

        private static String seconds(Instant instant) {
            return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        }
    }
}
