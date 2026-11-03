package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pages always read as an anonymous viewer, even with a login session, because that session only exists for approving
 * OAuth applications.
 */
@Controller
class PagesController {

    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    private final ActorService actors;
    private final StatusService statuses;
    private final InstanceProperties instance;
    private final ContentNegotiationManager contentNegotiation;
    private final RegistrationProperties registration;

    PagesController(
            ActorService actors,
            StatusService statuses,
            InstanceProperties instance,
            ContentNegotiationManager contentNegotiation,
            RegistrationProperties registration) {
        this.actors = actors;
        this.statuses = statuses;
        this.instance = instance;
        this.contentNegotiation = contentNegotiation;
        this.registration = registration;
    }

    @GetMapping("/")
    String home(Principal principal, Model model) {
        model.addAttribute("domain", instance.domain());
        model.addAttribute("userCount", actors.countLocal());
        model.addAttribute("statusCount", statuses.countLocal());
        model.addAttribute("showLogin", principal == null);
        model.addAttribute("registrationEnabled", registration.enabled());
        model.addAttribute("statuses", views(statuses.findPublic(firstPage(null))));
        return "home";
    }

    /**
     * This shares its URL with the actor document. It must not declare what it produces: requests that accept anything
     * would then match both mappings equally, which Spring rejects as ambiguous. Without it, only requests that ask for
     * an ActivityPub type get the actor document, and {@link #requireHtmlAccepted} rejects the rest.
     */
    @GetMapping("/users/{username}")
    String profile(
            @PathVariable String username,
            @RequestParam(name = "max_id", required = false) String maxId,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var actor = actors.findLocalByUsername(username).orElseThrow(PagesController::notFound);
        var page = firstPage(maxId == null ? null : Status.parseId(maxId).orElseThrow(PagesController::notFound));
        var found = statuses.findByAccount(actor.id(), ANONYMOUS, true, page);

        model.addAttribute("actor", actor);
        model.addAttribute("joined", LocalDate.ofInstant(actor.createdAt(), ZoneOffset.UTC));
        model.addAttribute("statuses", found.stream().map(status -> StatusView.from(status, actor)).toList());
        // Only a full page can have older statuses after it.
        model.addAttribute("olderThan", found.size() == page.limit() ? found.getLast().id() : null);
        return "profile";
    }

    /**
     * This shares its URL with the status's ActivityPub object, so like {@link #profile} it must not declare what it
     * produces.
     */
    @GetMapping("/users/{username}/statuses/{id}")
    String status(@PathVariable String username, @PathVariable String id, Model model, HttpServletRequest request)
            throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var view = Status.parseId(id)
                .flatMap(statusId -> statuses.findVisible(statusId, ANONYMOUS))
                .flatMap(this::view)
                // Otherwise any account's URL could show this status.
                .filter(found -> found.username().equalsIgnoreCase(username))
                .orElseThrow(PagesController::notFound);

        model.addAttribute("view", view);
        model.addAttribute("parent", Optional.ofNullable(view.status().inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, ANONYMOUS))
                .flatMap(this::view)
                .orElse(null));
        return "status";
    }

    /**
     * A client that can't read HTML, such as one that only asks for {@code application/json}, should get an error it can
     * understand rather than a page it fails to parse.
     */
    private void requireHtmlAccepted(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        var accepted = contentNegotiation.resolveMediaTypes(new ServletWebRequest(request));
        if (accepted.stream().noneMatch(MediaType.TEXT_HTML::isCompatibleWith)) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML));
        }
    }

    private Optional<StatusView> view(Status status) {
        return actors.findLocalById(status.actorId()).map(author -> StatusView.from(status, author));
    }

    /**
     * Leaves out statuses whose author can't be loaded, such as when the account was deleted after the statuses were read,
     * so that one of them doesn't fail the whole page.
     */
    private List<StatusView> views(List<Status> found) {
        var actorIds = found.stream().map(Status::actorId).collect(Collectors.toSet());
        var authors = actors.findLocalByIds(actorIds).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        return found.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .map(status -> StatusView.from(status, authors.get(status.actorId())))
                .toList();
    }

    private static StatusPage firstPage(UUID maxId) {
        return new StatusPage(StatusPage.DEFAULT_LIMIT, maxId, null, null);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
