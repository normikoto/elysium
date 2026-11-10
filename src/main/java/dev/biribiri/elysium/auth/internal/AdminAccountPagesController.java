package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.AccountFilter;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
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

import java.util.Optional;
import java.util.UUID;

/**
 * The admin page for suspending and deleting accounts. Everything under {@code /admin} is limited to admins and needs a CSRF
 * token, through the security configuration.
 */
@Controller
class AdminAccountPagesController {

    private static final String PATH = "/admin/accounts";

    private final ActorService actors;
    private final AccountModeration moderation;

    AdminAccountPagesController(ActorService actors, AccountModeration moderation) {
        this.actors = actors;
        this.moderation = moderation;
    }

    @GetMapping(PATH)
    String list(
            @RequestParam(name = "max_id", required = false) String maxId,
            @RequestParam(required = false) String username,
            Model model) {
        return page(Ids.parse(maxId).orElse(null), username, model);
    }

    @PostMapping(PATH + "/{id}/suspend")
    String suspend(@PathVariable String id, Authentication authentication, Model model, HttpServletResponse response) {
        return act(model, response, () -> moderation.suspend(parse(id), Optional.of(self(authentication))));
    }

    @PostMapping(PATH + "/{id}/unsuspend")
    String unsuspend(@PathVariable String id, Model model, HttpServletResponse response) {
        return act(model, response, () -> moderation.unsuspend(parse(id)));
    }

    @PostMapping(PATH + "/{id}/reject-media")
    String rejectMedia(@PathVariable String id, Model model, HttpServletResponse response) {
        return act(model, response, () -> moderation.setMediaRejected(parse(id), true));
    }

    @PostMapping(PATH + "/{id}/allow-media")
    String allowMedia(@PathVariable String id, Model model, HttpServletResponse response) {
        return act(model, response, () -> moderation.setMediaRejected(parse(id), false));
    }

    @GetMapping(PATH + "/{id}/delete")
    String confirmDelete(@PathVariable String id, Model model) {
        var account = actors.findById(parse(id))
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return confirmation(account, model);
    }

    /**
     * Typing the username stops a mistaken click from doing something that can't be undone.
     */
    @PostMapping(PATH + "/{id}/delete")
    String delete(
            @PathVariable String id,
            @RequestParam(required = false) String username,
            Authentication authentication,
            Model model,
            HttpServletResponse response) {
        var account = actors.findById(parse(id))
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!account.username().equals(username)) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", "That doesn't match the username.");
            return confirmation(account, model);
        }
        return act(model, response, () -> moderation.delete(account.id(), Optional.of(self(authentication))));
    }

    private String act(Model model, HttpServletResponse response, Runnable action) {
        try {
            action.run();
            return "redirect:" + PATH;
        } catch (AccountActionRefusedException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            return page(null, null, model);
        }
    }

    private String confirmation(Actor account, Model model) {
        model.addAttribute("account", Row.from(account, false, actors.isLocal(account), false));
        return "admin-account-delete";
    }

    private String page(UUID maxId, String username, Model model) {
        var prefix = username == null || username.isBlank() ? null : username.strip();
        var filter = new AccountFilter(null, false, prefix, null, AccountFilter.MAX_LIMIT, maxId, null, null);
        var found = actors.list(filter);
        var ids = found.stream().map(Actor::id).toList();
        var admins = moderation.adminsAmong(ids);
        var mediaRejected = moderation.mediaRejectedAmong(ids);
        model.addAttribute("accounts", found.stream()
                .map(account -> Row.from(account, admins.contains(account.id()), actors.isLocal(account),
                        mediaRejected.contains(account.id())))
                .toList());
        model.addAttribute("older", found.size() == filter.limit() ? found.getLast().id() : null);
        model.addAttribute("username", prefix == null ? "" : prefix);
        return "admin-accounts";
    }

    private static UUID parse(String id) {
        return Ids.parse(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * A logged in user's name is their actor ID: see {@link LocalAccounts#loadUserByUsername}.
     */
    private static UUID self(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    record Row(UUID id, String username, String handle, boolean admin, boolean local, boolean suspended,
               boolean mediaRejected) {

        static Row from(Actor account, boolean admin, boolean local, boolean mediaRejected) {
            return new Row(
                    account.id(),
                    account.username(),
                    local ? account.username() : account.username() + "@" + account.domain(),
                    admin,
                    local,
                    account.isSuspended(),
                    mediaRejected);
        }
    }
}
