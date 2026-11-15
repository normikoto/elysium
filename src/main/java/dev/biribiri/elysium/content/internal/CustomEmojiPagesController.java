package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * The admin page for this server's custom emoji. Everything under {@code /admin} is limited to admins and needs a CSRF
 * token, through the security configuration.
 */
@Controller
class CustomEmojiPagesController {

    private static final String PATH = "/admin/custom-emojis";

    private final CustomEmojis emojis;
    private final InstanceProperties instance;

    CustomEmojiPagesController(CustomEmojis emojis, InstanceProperties instance) {
        this.emojis = emojis;
        this.instance = instance;
    }

    @GetMapping(PATH)
    String list(Model model) {
        model.addAttribute("shortcode", "");
        model.addAttribute("category", "");
        return page(model);
    }

    @PostMapping(PATH)
    String add(
            @RequestParam(required = false) String shortcode,
            @RequestParam(required = false) MultipartFile image,
            @RequestParam(required = false) String category,
            Model model,
            HttpServletResponse response) throws IOException {
        try {
            if (image == null || image.isEmpty()) {
                throw new InvalidCustomEmojiException("Choose an image");
            }
            // Before the file is read into memory, since Spring takes files as large as a video.
            CustomEmojis.checkImageSize(image.getSize());
            emojis.addLocal(shortcode, image.getBytes(), category);
            return "redirect:" + PATH;
        } catch (InvalidCustomEmojiException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("shortcode", shortcode);
            model.addAttribute("category", category);
            return page(model);
        }
    }

    /**
     * A checkbox that isn't ticked isn't sent, so a missing {@code visibleInPicker} means off. An emoji that someone else
     * has removed meanwhile is left removed.
     */
    @PostMapping(PATH + "/{id}")
    String change(
            @PathVariable String id,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String visibleInPicker,
            Model model,
            HttpServletResponse response) {
        try {
            Ids.parse(id).ifPresent(emojiId -> emojis.updateLocal(emojiId, category, visibleInPicker != null));
            return "redirect:" + PATH;
        } catch (InvalidCustomEmojiException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("shortcode", "");
            model.addAttribute("category", "");
            return page(model);
        }
    }

    /**
     * Someone else may have removed it already, which is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/delete")
    String remove(@PathVariable String id) {
        Ids.parse(id).ifPresent(emojis::deleteLocal);
        return "redirect:" + PATH;
    }

    private String page(Model model) {
        model.addAttribute("emojis", emojis.listLocal().stream().map(emoji -> Row.from(emoji, instance)).toList());
        model.addAttribute("maxCategoryLength", CustomEmojis.MAX_CATEGORY_LENGTH);
        return "admin-custom-emojis";
    }

    record Row(UUID id, String shortcode, String url, String category, boolean visibleInPicker) {

        static Row from(CustomEmoji emoji, InstanceProperties instance) {
            return new Row(emoji.id(), emoji.shortcode(), emoji.url(instance).toString(),
                    emoji.category() == null ? "" : emoji.category(), emoji.visibleInPicker());
        }
    }
}
