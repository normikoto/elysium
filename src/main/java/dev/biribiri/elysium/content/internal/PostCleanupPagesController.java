package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.InvalidPostCleanupPolicyException;
import dev.biribiri.elysium.content.PostCleanupPolicy;
import dev.biribiri.elysium.content.PostCleanups;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * Lets someone have their old posts deleted automatically. There's no API for this, as there isn't in Mastodon. Every page
 * under {@code /settings} needs a login and a CSRF token, through the security configuration.
 */
@Controller
class PostCleanupPagesController {

    private static final String PATH = "/settings/post-cleanup";
    private static final String PAGE = "post-cleanup";

    private final PostCleanups cleanups;

    PostCleanupPagesController(PostCleanups cleanups) {
        this.cleanups = cleanups;
    }

    /**
     * What the page shows, which after a refused save is what was sent, so that nobody has to make their changes again. A
     * policy can't hold that, since it's refused.
     */
    record Form(
            boolean enabled,
            Integer minAgeDays,
            boolean keepPinned,
            boolean keepDirect,
            boolean keepPolls,
            boolean keepMedia,
            boolean keepSelfFavourited,
            boolean keepSelfBookmarked,
            Integer minFavourites,
            Integer minBoosts) {

        static Form of(PostCleanupPolicy policy) {
            return new Form(policy.enabled(), policy.minimumAge().days(), policy.keepPinned(), policy.keepDirect(),
                    policy.keepPolls(), policy.keepMedia(), policy.keepSelfFavourited(), policy.keepSelfBookmarked(),
                    policy.minFavourites(), policy.minBoosts());
        }

        PostCleanupPolicy policy() {
            var age = minAgeDays == null ? null : PostCleanupPolicy.MinimumAge.ofDays(minAgeDays).orElse(null);
            return new PostCleanupPolicy(enabled, age, keepPinned, keepDirect, keepPolls, keepMedia, keepSelfFavourited,
                    keepSelfBookmarked, minFavourites, minBoosts);
        }
    }

    @GetMapping(PATH)
    String show(@RequestParam(required = false) String saved, Authentication authentication, Model model) {
        model.addAttribute("saved", saved != null);
        return show(Form.of(cleanups.find(accountId(authentication))), model);
    }

    /**
     * An unchecked box isn't sent, so a missing one is off, and an empty number keeps nothing for it.
     */
    @PostMapping(PATH)
    String save(
            @RequestParam(defaultValue = "false") boolean enabled,
            @RequestParam(required = false) Integer minAgeDays,
            @RequestParam(defaultValue = "false") boolean keepPinned,
            @RequestParam(defaultValue = "false") boolean keepDirect,
            @RequestParam(defaultValue = "false") boolean keepPolls,
            @RequestParam(defaultValue = "false") boolean keepMedia,
            @RequestParam(defaultValue = "false") boolean keepSelfFavourited,
            @RequestParam(defaultValue = "false") boolean keepSelfBookmarked,
            @RequestParam(required = false) Integer minFavourites,
            @RequestParam(required = false) Integer minBoosts,
            Authentication authentication,
            Model model,
            HttpServletResponse response) {
        var form = new Form(enabled, minAgeDays, keepPinned, keepDirect, keepPolls, keepMedia, keepSelfFavourited,
                keepSelfBookmarked, minFavourites, minBoosts);
        try {
            cleanups.save(accountId(authentication), form.policy());
        } catch (InvalidPostCleanupPolicyException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("saved", false);
            model.addAttribute("error", e.getMessage());
            return show(form, model);
        }
        return "redirect:" + PATH + "?saved";
    }

    private static String show(Form form, Model model) {
        model.addAttribute("form", form);
        model.addAttribute("ages", List.of(PostCleanupPolicy.MinimumAge.values()));
        return PAGE;
    }

    /**
     * A logged in user's name is their actor ID.
     */
    private static UUID accountId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }
}
