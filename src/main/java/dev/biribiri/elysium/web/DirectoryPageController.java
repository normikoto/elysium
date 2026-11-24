package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.AccountDirectory;
import dev.biribiri.elysium.text.EmojiHtml;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * The public page of this server's accounts that choose to be listed. Accounts on other servers are left out, as they
 * are from the rest of the public pages, which show what's here.
 */
@Controller
class DirectoryPageController {

    static final int PAGE_SIZE = 40;

    /**
     * How far the page can be paged through, since the accounts it skips still have to be sorted, so that a crawler can't
     * make each request sort every listed account.
     */
    static final int MAX_PAGES = 50;

    private final AccountDirectory directory;
    private final ProfileService profiles;
    private final LocalEmojiUrls emojiUrls;
    private final InstanceProperties instance;

    DirectoryPageController(
            AccountDirectory directory, ProfileService profiles, LocalEmojiUrls emojiUrls, InstanceProperties instance) {
        this.directory = directory;
        this.profiles = profiles;
        this.emojiUrls = emojiUrls;
        this.instance = instance;
    }

    /**
     * An account's entry.
     *
     * @param bio the bio as HTML that's safe to show
     */
    record EntryView(String username, AuthorView author, String bio) {
    }

    @GetMapping("/directory")
    String directory(
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String page,
            Model model) {
        var sort = AccountDirectory.Order.parse(order).orElseThrow(DirectoryPageController::notFound);
        var pageNumber = pageNumber(page);
        var listed = directory.list(Optional.empty(), sort, true, (pageNumber - 1) * PAGE_SIZE, PAGE_SIZE)
                .orElseThrow(DirectoryPageController::notFound);
        var profilesById = profiles.findAll(listed.stream().map(Actor::id).toList());
        var emoji = emojiUrls.in(profilesById.values().stream().map(Profile::note).toList());

        model.addAttribute("order", sort.value());
        model.addAttribute("entries", listed.stream().map(actor -> {
            var profile = profilesById.getOrDefault(actor.id(), Profile.EMPTY);
            return new EntryView(actor.username(), AuthorView.of(actor).withProfile(profile, instance),
                    EmojiHtml.render(profile.noteHtml(instance.baseUrl()), emoji));
        }).toList());
        model.addAttribute("nextPage", listed.size() == PAGE_SIZE && pageNumber < MAX_PAGES ? pageNumber + 1 : null);
        return "directory";
    }

    private static int pageNumber(String value) {
        if (value == null || value.isEmpty()) {
            return 1;
        }
        try {
            var number = Integer.parseInt(value);
            if (number < 1 || number > MAX_PAGES) {
                throw notFound();
            }
            return number;
        } catch (NumberFormatException e) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
