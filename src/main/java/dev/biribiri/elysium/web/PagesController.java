package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.auth.Admins;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.BoostStats;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.LikeStats;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusOrigin;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.text.Hashtags;
import jakarta.servlet.http.HttpServletRequest;
import dev.biribiri.elysium.text.EmojiHtml;
import dev.biribiri.elysium.timeline.TimelineService;
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
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pages read as an anonymous viewer, even with a login session, so that a page someone shares shows everyone the same.
 * A status's own page is the exception: it's where apps link every status but a boost, so it shows the logged-in account
 * what it could see in the API. A profile's follow controls are the other: they're the viewer's own, and change nothing
 * else on the page.
 */
@Controller
class PagesController {

    private static final Optional<UUID> ANONYMOUS = Optional.empty();

    /**
     * As many statuses as a client gets by default.
     */
    private static final int PAGE_SIZE = 20;

    private static final DateTimeFormatter JOINED = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final ActorService actors;
    private final StatusService statuses;
    private final InstanceProperties instance;
    private final ContentNegotiationManager contentNegotiation;
    private final RegistrationProperties registration;
    private final ProfileService profiles;
    private final MediaAttachments attachments;
    private final PollService polls;
    private final StatusPinService pins;
    private final CustomEmojis emojis;
    private final Admins admins;
    private final FollowService follows;
    private final LikeService likes;
    private final BoostService boosts;
    private final TimelineService timelines;

    PagesController(
            ActorService actors,
            StatusService statuses,
            InstanceProperties instance,
            ContentNegotiationManager contentNegotiation,
            RegistrationProperties registration,
            ProfileService profiles,
            MediaAttachments attachments,
            PollService polls,
            StatusPinService pins,
            CustomEmojis emojis,
            Admins admins,
            FollowService follows,
            LikeService likes,
            BoostService boosts,
            TimelineService timelines) {
        this.timelines = timelines;
        this.likes = likes;
        this.boosts = boosts;
        this.admins = admins;
        this.follows = follows;
        this.pins = pins;
        this.emojis = emojis;
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
        model.addAttribute("domain", instance.accountDomain());
        model.addAttribute("userCount", actors.countLocal());
        model.addAttribute("statusCount", statuses.countLocal());
        model.addAttribute("showAdmin", viewer(principal).filter(admins::isAdmin).isPresent());
        model.addAttribute("registrationEnabled", registration.acceptsUninvited());
        model.addAttribute("statuses", views(timelines.publicTimeline(StatusOrigin.LOCAL, firstPage(null), false, ANONYMOUS)));
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
            Principal principal,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        return profile(ProfileTab.POSTS, username, maxId, principal, model, request);
    }

    @GetMapping("/users/{username}/with_replies")
    String profileWithReplies(
            @PathVariable String username,
            @RequestParam(name = "max_id", required = false) String maxId,
            Principal principal,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        return profile(ProfileTab.WITH_REPLIES, username, maxId, principal, model, request);
    }

    @GetMapping("/users/{username}/media")
    String profileMedia(
            @PathVariable String username,
            @RequestParam(name = "max_id", required = false) String maxId,
            Principal principal,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        return profile(ProfileTab.MEDIA, username, maxId, principal, model, request);
    }

    private String profile(
            ProfileTab tab, String username, String maxId, Principal principal, Model model, HttpServletRequest request)
            throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var actor = actors.findLocalByUsername(username).orElseThrow(PagesController::notFound);
        var page = firstPage(maxId == null ? null : Ids.parse(maxId).orElseThrow(PagesController::notFound));
        var found = statuses.findByAccount(actor.id(), ANONYMOUS, tab.excludeReplies, false, tab.onlyMedia, page);

        var profile = profiles.find(actor.id());
        model.addAttribute("actor", actor);
        model.addAttribute("tab", tab);
        model.addAttribute("postCount", statuses.countVisibleToAnyone(actor.id()));
        model.addAttribute("followingCount", follows.countFollowing(actor.id()));
        model.addAttribute("followerCount", follows.countFollowers(actor.id()));
        model.addAttribute("follow", FollowControls.of(viewer(principal), actor, follows));
        model.addAttribute("profile", profile);
        var profileEmojis = new HashMap<String, String>();
        emojis.findLocalIn(profile.texts()).forEach((shortcode, emoji) ->
                profileEmojis.put(shortcode, emoji.url(instance).toString()));
        model.addAttribute("bio", EmojiHtml.render(profile.noteHtml(instance.baseUrl()), profileEmojis));
        model.addAttribute("fields", profile.fields().stream().map(field -> new FieldView(
                EmojiHtml.renderText(field.name(), profileEmojis),
                EmojiHtml.render(field.valueHtml(), profileEmojis),
                field.verifiedAt() != null)).toList());
        model.addAttribute("name", profile.displayName().isEmpty() ? "@" + actor.username() : profile.displayName());
        model.addAttribute("avatar", profile.avatar() == null ? null : instance.resolve(profile.avatar().path()));
        model.addAttribute("header", profile.header() == null ? null : instance.resolve(profile.header().path()));
        model.addAttribute("background",
                profile.background() == null ? null : instance.resolve(profile.background().path()));
        model.addAttribute("bot", profile.bot());
        model.addAttribute("cat", profile.cat());
        model.addAttribute("joined", JOINED.format(LocalDate.ofInstant(actor.createdAt(), ZoneOffset.UTC)));
        // Above the first page only, and still in their place below, as on Mastodon.
        model.addAttribute("pinned", maxId == null && tab == ProfileTab.POSTS
                ? profileViews(pins.findPinned(actor.id(), ANONYMOUS), actor) : List.of());
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
    String status(
            @PathVariable String username,
            @PathVariable String id,
            Principal principal,
            Model model,
            HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var viewer = viewer(principal);
        var found = Ids.parse(id).flatMap(statusId -> statuses.findVisible(statusId, viewer));
        var boost = found.filter(Status::isReblog);
        if (boost.isPresent()) {
            // A boost has no page of its own, so its link leads to what it boosts, as on Mastodon.
            return boost.filter(status -> actors.findLocalById(status.actorId())
                            .filter(booster -> booster.username().equalsIgnoreCase(username)).isPresent())
                    .flatMap(status -> statuses.findVisible(status.reblogOfId(), viewer))
                    .map(original -> "redirect:" + original.link())
                    .orElseThrow(PagesController::notFound);
        }
        var view = found
                .flatMap(this::view)
                // Otherwise any account's URL could show this status.
                .filter(shown -> shown.authorName().equalsIgnoreCase("@" + username))
                .orElseThrow(PagesController::notFound);

        model.addAttribute("view", view);
        model.addAttribute("parent", Optional.ofNullable(view.status().inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, viewer))
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
        var found = timelines.tagTimeline(TagFilter.of(tag), StatusOrigin.ANY, page, false, ANONYMOUS);

        model.addAttribute("tag", tag);
        model.addAttribute("statuses", profileViews(found, null));
        model.addAttribute("olderThan", found.size() == page.limit() ? found.getLast().id() : null);
        return "tag";
    }

    @GetMapping({"/users/{username}/statuses/{id}/likes", "/users/{username}/statuses/{id}/shares"})
    String collection(
            @PathVariable String username, @PathVariable String id, Principal principal, HttpServletRequest request)
            throws HttpMediaTypeNotAcceptableException {
        requireHtmlAccepted(request);
        var author = actors.findAnyLocalByUsername(username).filter(actor -> !actor.isDeleted())
                .orElseThrow(PagesController::notFound);
        if (author.isSuspended()) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var found = Ids.parse(id).flatMap(statusId -> statuses.findVisible(statusId, viewer(principal)))
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
     * One lookup for every status on a page, for each of its attachments, polls, emoji, authors' profiles, and counts.
     */
    private List<StatusView> withAttachments(List<StatusView> views) {
        var ids = views.stream().map(view -> view.status().id()).distinct().toList();
        var found = attachments.findByStatuses(ids);
        var foundPolls = polls.findAll(ids, Optional.empty());
        var foundEmojis = emojis.findForStatuses(ids);
        var authorIds = views.stream().map(view -> view.status().actorId()).collect(Collectors.toSet());
        var foundProfiles = profiles.findAll(authorIds);
        var likeStats = likes.stats(ids, ANONYMOUS);
        var boostStats = boosts.stats(ids, ANONYMOUS);
        var now = Instant.now();
        return views.stream()
                .map(view -> view.withAuthor(view.author().withProfile(
                        foundProfiles.getOrDefault(view.status().actorId(), Profile.EMPTY), instance)))
                .map(view -> view.withCounts(
                        Optional.ofNullable(boostStats.get(view.status().id())).map(BoostStats::count).orElse(0L),
                        Optional.ofNullable(likeStats.get(view.status().id())).map(LikeStats::count).orElse(0L)))
                .map(view -> view.withEmojis(foundEmojis.getOrDefault(view.status().id(), List.of()).stream()
                        .collect(Collectors.toMap(CustomEmoji::shortcode, emoji -> emoji.url(instance).toString(),
                                (first, second) -> first))))
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

    /**
     * A profile field, as HTML that's safe to show.
     */
    record FieldView(String name, String value, boolean verified) {
    }

    /**
     * The logged-in account, whose name is its actor ID. A session that outlived its account has already been ended by the
     * time this runs.
     */
    private static Optional<UUID> viewer(Principal principal) {
        return Optional.ofNullable(principal).map(Principal::getName).flatMap(Ids::parse);
    }

    private static IdPage firstPage(UUID maxId) {
        return new IdPage(PAGE_SIZE, maxId, null, null);
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
