package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.ModeratedStatus;
import dev.biribiri.elysium.content.ModeratedStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The admin page for removing statuses that break the rules. Everything under {@code /admin} is limited to admins and needs
 * a CSRF token, through the security configuration.
 */
@Controller
class StatusRemovalPagesController {

    private static final String PATH = "/admin/statuses";

    private final StatusService statuses;
    private final ModeratedStatuses rows;

    StatusRemovalPagesController(StatusService statuses, ModeratedStatuses rows) {
        this.statuses = statuses;
        this.rows = rows;
    }

    @GetMapping(PATH)
    String find(@RequestParam(required = false) String q, @RequestParam(required = false) String removed, Model model,
                HttpServletResponse response) {
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("removed", removed != null);
        var found = statuses.findForModeration(q).flatMap(this::row);
        if (q != null && !q.isBlank() && found.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("error", "No status was found with that URL or ID.");
        }
        model.addAttribute("status", found.orElse(null));
        return "admin-statuses";
    }

    /**
     * Removing can't be undone, and for a local status reaches other servers, so it's confirmed on a page of its own.
     */
    @GetMapping(PATH + "/{id}/remove")
    String confirm(@PathVariable String id, Model model) {
        var found = statuses.findForModeration(parse(id).toString()).flatMap(this::row)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("status", found);
        return "admin-status-remove";
    }

    /**
     * A status that's gone meanwhile, whoever removed it, is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/remove")
    String remove(@PathVariable String id, Authentication authentication) {
        statuses.remove(parse(id), self(authentication));
        return "redirect:" + PATH + "?removed";
    }

    private static UUID parse(String id) {
        return Ids.parse(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Admins see only what anyone could, so that removing statuses doesn't mean reading followers-only or direct ones.
     */
    private Optional<ModeratedStatus> row(Status status) {
        return rows.rows(List.of(status), false).stream().findFirst();
    }

    /**
     * A logged in user's name is their actor ID.
     */
    private static UUID self(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
