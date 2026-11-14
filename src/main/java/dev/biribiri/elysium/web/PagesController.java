package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.text.Hashtags;
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
import java.util.HashSet;
import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final ProfileService profiles;
    private final MediaAttachments attachments;
    private final PollService polls;
    private final StatusPinService pins;

    PagesController(
            ActorService actors,
            StatusService statuses,
            InstanceProperties instance,
            ContentNegotiationManager contentNegotiation,
            RegistrationProperties registration,
            ProfileService profiles,
            MediaAttachments attachments,
            PollService polls,
            StatusPinService pins) {
        this.pins = pins;
        this.attachments = attachments;
        this.polls = polls;
        this.actors = actors;
        this.profiles = profiles;
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
        model.addAttribute("registrationEnabled", registration.acceptsUninvited());
        model.addAttribute("statuses", views(statuses.findPublic(StatusOrigin.LOCAL, firstPage(null))));
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
        var page = firstPage(maxId == null ? null : Ids.parse(maxId).orElseThrow(PagesController::notFound));
        var found = statuses.findByAccount(actor.id(), ANONYMOUS, true, false, page);

        var profile = profiles.find(actor.id());
        model.addAttribute("actor", actor);
        model.addAttribute("profile", profile);
        model.addAttribute("bio", profile.noteHtml(instance.baseUrl()));
        model.addAttribute("name", profile.displayName().isEmpty() ? "@" + actor.username() : profile.displayName());
        model.addAttribute("avatar", profile.avatar() == null ? null : instance.resolve(profile.avatar().path()));
        model.addAttribute("header", profile.header() == null ? null : instance.resolve(profile.header().path()));
        model.addAttribute("joined", LocalDate.ofInstant(actor.createdAt(), ZoneOffset.UTC));
        // Above the first page only, and still in their place below, as on Mastodon.
        model.addAttribute("pinned", maxId == null ? profileViews(pins.findPinned(actor.id(), ANONYMOUS), actor) : List.of());
        model.addAttribute("statuses", profileViews(found, actor));
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
        var found = Ids.parse(id).flatMap(statusId -> statuses.findVisible(statusId, ANONYMOUS));
        var boost = found.filter(Status::isReblog);
        if (boost.isPresent()) {
            // A boost has no page of its own, so its link leads to what it boosts, as on Mastodon.
            return boost.filter(status -> actors.findLocalById(status.actorId())
                            .filter(booster -> booster.username().equalsIgnoreCase(username)).isPresent())
                    .flatMap(status -> profileViews(List.of(status), null).stream().findFirst())
                    .map(view -> "redirect:" + view.url())
                    .orElseThrow(PagesController::notFound);
        }
        var view = found
                .flatMap(this::view)
                // Otherwise any account's URL could show this status.
                .filter(shown -> shown.authorName().equalsIgnoreCase("@" + username))
                .orElseThrow(PagesController::notFound);

        model.addAttribute("view", view);
        model.addAttribute("parent", Optional.ofNullable(view.status().inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, ANONYMOUS))
                .flatMap(this::view)
                .orElse(null));
        return "status";
    }

    /**
     * Where hashtags link to, here and on other servers, which is why this is a page for people rather than an ActivityPub
     * collection. It lists public statuses from any server, as Mastodon's does.
     */
    @GetMapping("/tags/{name}")
    String tag(
            @PathVariable String name,
            @RequestParam(name = "max_id", required = false) String maxId,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var tag = Hashtags.parse(name).orElseThrow(PagesController::notFound);
        var page = firstPage(maxId == null ? null : Ids.parse(maxId).orElseThrow(PagesController::notFound));
        var found = statuses.findTagged(TagFilter.of(tag), StatusOrigin.ANY, page);

        model.addAttribute("tag", tag);
        model.addAttribute("statuses", profileViews(found, null));
        model.addAttribute("olderThan", found.size() == page.limit() ? found.getLast().id() : null);
        return "tag";
    }

    @GetMapping({"/users/{username}/statuses/{id}/likes", "/users/{username}/statuses/{id}/shares"})
    String collection(@PathVariable String username, @PathVariable String id, HttpServletRequest request)
            throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var author = actors.findAnyLocalByUsername(username).filter(actor -> !actor.isDeleted())
                .orElseThrow(PagesController::notFound);
        if (author.isSuspended()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var found = Ids.parse(id).flatMap(statusId -> statuses.findVisible(statusId, ANONYMOUS))
                .filter(status -> !status.isReblog() && status.actorId().equals(author.id()))
                .orElseThrow(PagesController::notFound);
        return "redirect:" + found.uri();
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
        return actors.findLocalById(status.actorId())
                .map(author -> StatusView.from(status, author))
                .map(found -> withAttachments(List.of(found)).getFirst());
    }

    /**
     * One lookup for every status on a page, for its attachments and one for its polls.
     */
    private List<StatusView> withAttachments(List<StatusView> views) {
        var ids = views.stream().map(view -> view.status().id()).distinct().toList();
        var found = attachments.findByStatuses(ids);
        var foundPolls = polls.findAll(ids, Optional.empty());
        var now = Instant.now();
        return views.stream()
                .map(view -> view.withAttachments(found.getOrDefault(view.status().id(), List.of()).stream()
                        .map(attachment -> AttachmentView.of(attachment, instance))
                        .toList()))
                .map(view -> Optional.ofNullable(foundPolls.get(view.status().id()))
                        .map(poll -> view.withPoll(PollView.of(poll, now)))
                        .orElse(view))
                .toList();
    }

    /**
     * Shows each boost as the status it boosts. Statuses and boosted statuses can be from another server. A boost whose
     * status can't be shown is left out.
     *
     * @param owner the local account whose statuses these are, or null to look up each booster
     */
    private List<StatusView> profileViews(List<Status> found, Actor owner) {
        var originals = statuses.findVisibleToAnyone(found.stream()
                        .filter(Status::isReblog).map(Status::reblogOfId).toList()).stream()
                .collect(Collectors.toMap(Status::id, Function.identity()));
        var actorIds = found.stream().map(Status::actorId).collect(Collectors.toCollection(HashSet::new));
        originals.values().forEach(original -> actorIds.add(original.actorId()));
        var authors = actors.findByIds(actorIds).stream()
                .filter(Actor::isActive)
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        return found.stream()
                .flatMap(status -> {
                    var shown = status.isReblog() ? originals.get(status.reblogOfId()) : status;
                    var author = shown == null ? null : authors.get(shown.actorId());
                    var booster = owner != null ? owner : authors.get(status.actorId());
                    if (author == null || booster == null) {
                        return Stream.empty();
                    }
                    return Stream.of(status.isReblog()
                            ? StatusView.boosted(shown, author, actors.isLocal(author), booster)
                            : StatusView.of(status, author, actors.isLocal(author)));
                })
                .collect(Collectors.collectingAndThen(Collectors.toList(), this::withAttachments));
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
                .collect(Collectors.collectingAndThen(Collectors.toList(), this::withAttachments));
    }

    private static StatusPage firstPage(UUID maxId) {
        return new StatusPage(StatusPage.DEFAULT_LIMIT, maxId, null, null);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
