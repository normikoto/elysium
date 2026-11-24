package dev.biribiri.elysium.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.Profile;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.content.AccountDirectory;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.text.EmojiHtml;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The public page of this server's accounts that choose to be listed. Accounts on other servers are left out, as they
 * are from the rest of the public pages, which show what's here.
 */
@Controller
class DirectoryPageController {

    static final int PAGE_SIZE = 40;

    /**
     * How far the page can be paged through, since each page reads every listed account's latest status before it skips
     * any.
     */
    static final int MAX_PAGES = 50;

    private final AccountDirectory directory;
    private final ActorService actors;
    private final ProfileService profiles;
    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    DirectoryPageController(
            AccountDirectory directory,
            ActorService actors,
            ProfileService profiles,
            CustomEmojis emojis,
            InstanceProperties instance) {
        this.directory = directory;
        this.actors = actors;
        this.profiles = profiles;
        this.emojis = emojis;
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
        if (!profiles.directoryShown()) {
            throw notFound();
        }
        var sort = AccountDirectory.Order.parse(order).orElseThrow(DirectoryPageController::notFound);
        var pageNumber = pageNumber(page);
        var ids = directory.list(Optional.empty(), sort, true, (pageNumber - 1) * PAGE_SIZE, PAGE_SIZE);
        var found = actors.findByIds(ids).stream().collect(Collectors.toMap(Actor::id, Function.identity()));
        var listed = ids.stream().map(found::get).filter(Objects::nonNull).toList();
        var profilesById = profiles.findAll(ids);

        var texts = new ArrayList<String>();
        profilesById.values().forEach(profile -> texts.add(profile.note()));
        var emojiUrls = new HashMap<String, String>();
        emojis.findLocalIn(texts).forEach((shortcode, emoji) -> emojiUrls.put(shortcode, emoji.url(instance).toString()));

        model.addAttribute("order", sort == AccountDirectory.Order.NEW ? "new" : "active");
        model.addAttribute("entries", listed.stream().map(actor -> {
            var profile = profilesById.getOrDefault(actor.id(), Profile.EMPTY);
            return new EntryView(actor.username(), AuthorView.of(actor).withProfile(profile, instance),
                    EmojiHtml.render(profile.noteHtml(instance.baseUrl()), emojiUrls));
        }).toList());
        model.addAttribute("nextPage", ids.size() == PAGE_SIZE && pageNumber < MAX_PAGES ? pageNumber + 1 : null);
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
