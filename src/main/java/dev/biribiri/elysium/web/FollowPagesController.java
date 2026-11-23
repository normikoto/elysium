package dev.biribiri.elysium.web;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteActorResolver.ResolveLimitException;
import dev.biribiri.elysium.actor.RemoteFollowPages;
import dev.biribiri.elysium.content.RemoteUrlResolver;
import dev.biribiri.elysium.graph.FollowNotAllowedException;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Following from web pages. A logged-in user follows here, from a profile or from {@code /authorize_interaction}, which is
 * where other servers' remote follow sends them. A visitor from another server is sent to their own server instead, which
 * is the one that has to send the {@code Follow}.
 */
@Controller
class FollowPagesController {

    static final String AUTHORIZE_INTERACTION_PATH = "/authorize_interaction";

    /**
     * Each lookup makes this server send a request to a server the visitor names, and anyone can ask, so this is counted
     * by address.
     */
    private static final int REMOTE_FOLLOWS_PER_MINUTE = 10;
    private static final int MAX_TRACKED_ADDRESSES = 100_000;

    private final ActorService actors;
    private final ProfileService profiles;
    private final FollowService follows;
    private final RemoteActorResolver remoteActors;
    private final RemoteUrlResolver urls;
    private final RemoteFollowPages followPages;
    private final DomainBlocks domainBlocks;
    private final InstanceProperties instance;
    private final ClientAddresses clients;
    private final RateLimiter remoteFollows;

    FollowPagesController(
            ActorService actors,
            ProfileService profiles,
            FollowService follows,
            RemoteActorResolver remoteActors,
            RemoteUrlResolver urls,
            RemoteFollowPages followPages,
            DomainBlocks domainBlocks,
            InstanceProperties instance,
            ClientAddresses clients) {
        this.actors = actors;
        this.profiles = profiles;
        this.follows = follows;
        this.remoteActors = remoteActors;
        this.urls = urls;
        this.followPages = followPages;
        this.domainBlocks = domainBlocks;
        this.instance = instance;
        this.clients = clients;
        this.remoteFollows = new RateLimiter(
                REMOTE_FOLLOWS_PER_MINUTE, Duration.ofMinutes(1), MAX_TRACKED_ADDRESSES, Ticker.systemTicker());
    }

    @PostMapping("/follow")
    String follow(@RequestParam String account, Principal principal, Model model, HttpServletResponse response) {
        var follower = viewerAccount(principal);
        var followed = followable(account);
        if (followed.id().equals(follower.id())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        try {
            follows.follow(follower, followed);
        } catch (FollowNotAllowedException e) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return page(model, followed, Optional.of(follower.id()), "You can't follow this account.");
        }
        return "redirect:" + pageOf(followed);
    }

    /**
     * Also withdraws a request that's waiting. An account can be unfollowed whatever its state, as through the API.
     */
    @PostMapping("/unfollow")
    String unfollow(@RequestParam String account, Principal principal) {
        var follower = viewerAccount(principal);
        var followed = Ids.parse(account).flatMap(actors::findById).orElseThrow(FollowPagesController::notFound);
        follows.unfollow(follower, followed.id());
        return followed.isActive() ? "redirect:" + pageOf(followed) : "redirect:/";
    }

    @PostMapping("/users/{username}/remote-follow")
    String remoteFollow(
            @PathVariable String username,
            @RequestParam(defaultValue = "") String address,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response) {
        var target = actors.findLocalByUsername(username).orElseThrow(FollowPagesController::notFound);
        model.addAttribute("target", target);
        model.addAttribute("targetAddress", "@" + target.username() + "@" + target.domain());
        model.addAttribute("address", address);
        var parsed = Address.parse(address);
        if (parsed.isEmpty()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("error", "Enter your address, such as @you@your.server.");
            return "remote-follow";
        }
        if (instance.isLocalDomain(parsed.get().domain())) {
            return "redirect:" + authorizeInteraction("acct:" + target.username() + "@" + target.domain());
        }
        var key = clients.keyFor(request);
        if (remoteFollows.tryAcquire(key).isEmpty()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, RateLimiter.retryAfterSeconds(remoteFollows.timeUntilReset(key)));
            model.addAttribute("error", "Too many attempts. Wait a minute, then try again.");
            return "remote-follow";
        }
        var found = followPages.find(parsed.get().username(), parsed.get().domain(), target.uri());
        if (found.isEmpty()) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", "Your server couldn't be asked, or doesn't say where to follow from.");
            return "remote-follow";
        }
        return "redirect:" + found.get();
    }

    /**
     * Takes what other servers' remote follow fills in, which is an {@code acct:} address or the actor's URL, as Mastodon's
     * does. Accounts are followed here, but statuses can only be opened in an app.
     */
    @GetMapping(AUTHORIZE_INTERACTION_PATH)
    String authorizeInteraction(
            @RequestParam(defaultValue = "") String uri, Principal principal, Model model, HttpServletResponse response) {
        var viewer = viewerAccount(principal);
        Optional<Actor> found;
        try {
            var address = Address.parse(uri);
            if (address.isPresent()) {
                found = instance.isLocalDomain(address.get().domain())
                        ? actors.findLocalByUsername(address.get().username())
                        : remoteActors.resolveAcct(viewer.id(), address.get().username(), address.get().domain());
            } else {
                var url = httpsUrl(uri);
                if (url.isEmpty()) {
                    found = Optional.empty();
                } else if (url.get().getHost().equalsIgnoreCase(instance.baseUrl().getHost())) {
                    found = actors.findLocalByUri(url.get());
                } else {
                    var resolved = urls.resolveUrl(viewer.id(), url.get());
                    if (resolved.filter(RemoteUrlResolver.Found.Post.class::isInstance).isPresent()) {
                        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
                        model.addAttribute("error", "This is a post. Open it in your app to reply, like, or boost it.");
                        return "authorize-interaction";
                    }
                    found = resolved.flatMap(result -> result instanceof RemoteUrlResolver.Found.Account(var actor)
                            ? Optional.of(actor) : Optional.empty());
                }
            }
        } catch (ResolveLimitException e) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            model.addAttribute("error", "Too many lookups of accounts on other servers. Wait a minute, then try again.");
            return "authorize-interaction";
        }
        if (found.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("error", "This account couldn't be found.");
            return "authorize-interaction";
        }
        if (actors.isLocal(found.get())) {
            return "redirect:" + pageOf(found.get());
        }
        return page(model, found.get(), Optional.of(viewer.id()), null);
    }

    private String page(Model model, Actor account, Optional<UUID> viewer, String error) {
        var profile = profiles.find(account.id());
        var controls = FollowControls.of(viewer, account, follows);
        model.addAttribute("follow", controls);
        model.addAttribute("name", profile.displayName().isEmpty() ? controls.address() : profile.displayName());
        model.addAttribute("error", error);
        return "authorize-interaction";
    }

    /**
     * A remote account on a server that's been blocked since it was stored is left out. The block is checked on the host
     * of its URI, as the API's follow checks it, since that's where the {@code Follow} would go, and its address can be on
     * another domain.
     */
    private Actor followable(String id) {
        var account = Ids.parse(id).flatMap(actors::findById).filter(Actor::isActive)
                .orElseThrow(FollowPagesController::notFound);
        if (!actors.isLocal(account) && domainBlocks.isBlocked(account.uri().getHost())) {
            throw notFound();
        }
        return account;
    }

    /**
     * Where the follow controls for an account are, which is never taken from the request, so that these forms can't send
     * anyone to another site.
     */
    private String pageOf(Actor account) {
        return actors.isLocal(account)
                ? UriComponentsBuilder.fromPath("/users/{username}").buildAndExpand(account.username()).encode().toUriString()
                : authorizeInteraction(account.uri().toString());
    }

    private static String authorizeInteraction(String uri) {
        return UriComponentsBuilder.fromPath(AUTHORIZE_INTERACTION_PATH)
                .queryParam("uri", "{uri}")
                .encode()
                .buildAndExpand(uri)
                .toUriString();
    }

    private Actor viewerAccount(Principal principal) {
        return Optional.ofNullable(principal).map(Principal::getName).flatMap(Ids::parse).flatMap(actors::findLocalById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    private static Optional<URI> httpsUrl(String value) {
        try {
            var url = new URI(value.strip());
            return "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null ? Optional.of(url) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /**
     * An address as people write it: {@code @user@domain}, {@code user@domain}, or {@code acct:user@domain}. Whether the
     * parts are valid is left to the lookup.
     */
    record Address(String username, String domain) {

        static Optional<Address> parse(String value) {
            var address = value.strip();
            if (address.regionMatches(true, 0, "acct:", 0, 5)) {
                address = address.substring(5);
            }
            if (address.startsWith("@")) {
                address = address.substring(1);
            }
            // A URL can have an @ in its path, such as Mastodon's profile pages.
            if (address.contains("/")) {
                return Optional.empty();
            }
            var separator = address.indexOf('@');
            if (separator <= 0 || separator == address.length() - 1 || address.indexOf('@', separator + 1) >= 0) {
                return Optional.empty();
            }
            return Optional.of(new Address(address.substring(0, separator), address.substring(separator + 1)
                    .toLowerCase(Locale.ROOT)));
        }
    }
}
