package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
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

import java.net.URI;
import java.time.temporal.ChronoUnit;
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
    private final ActorService actors;
    private final MediaAttachments attachments;

    StatusRemovalPagesController(StatusService statuses, ActorService actors, MediaAttachments attachments) {
        this.statuses = statuses;
        this.actors = actors;
        this.attachments = attachments;
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
     * A status that's gone meanwhile, whoever removed it, is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/remove")
    String remove(@PathVariable String id, Authentication authentication) {
        Ids.parse(id).ifPresent(statusId -> statuses.remove(statusId, self(authentication)));
        return "redirect:" + PATH + "?removed";
    }

    private Optional<Row> row(Status status) {
        return actors.findById(status.actorId()).map(author -> {
            var local = actors.isLocal(author);
            // Admins see only what anyone could, so that removing statuses doesn't mean reading followers-only or direct ones.
            var shown = status.isVisibleTo(Optional.empty());
            var media = shown
                    ? attachments.findByStatuses(List.of(status.id())).getOrDefault(status.id(), List.of())
                    : List.<MediaAttachment>of();
            return new Row(
                    status.id(),
                    local ? author.username() : author.username() + "@" + author.domain(),
                    local,
                    author.isSuspended(),
                    status.visibility().value(),
                    status.publishedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                    status.url() != null ? status.url() : status.uri(),
                    shown,
                    shown ? status.spoilerText() : "",
                    shown ? status.content() : "",
                    media.stream().map(attachment -> attachment.description() == null ? "" : attachment.description())
                            .toList());
        });
    }

    /**
     * A logged in user's name is their actor ID.
     */
    private static UUID self(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    /**
     * @param shown        whether anyone can see it, which is when its content is shown here
     * @param content      sanitized HTML, as it's shown on its public page
     * @param descriptions one for each attachment, empty for one without
     */
    record Row(UUID id, String handle, boolean local, boolean authorSuspended, String visibility, String publishedAt,
               URI link, boolean shown, String spoilerText, String content, List<String> descriptions) {
    }
}
