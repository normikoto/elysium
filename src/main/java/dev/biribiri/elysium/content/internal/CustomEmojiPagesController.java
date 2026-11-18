package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.CustomEmoji;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import dev.biribiri.elysium.media.Images;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The admin page for this server's custom emoji. Everything under {@code /admin} is limited to admins and needs a CSRF
 * token, through the security configuration.
 */
@Controller
class CustomEmojiPagesController {

    private static final String PATH = "/admin/custom-emojis";

    private final CustomEmojis emojis;
    private final EmojiPacks packs;
    private final InstanceProperties instance;

    CustomEmojiPagesController(CustomEmojis emojis, EmojiPacks packs, InstanceProperties instance) {
        this.emojis = emojis;
        this.packs = packs;
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
            emojis.checkImageSize(image.getSize());
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
            return refused(e, model, response);
        }
    }

    @PostMapping(PATH + "/categories")
    String renameCategory(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Model model,
            HttpServletResponse response) {
        try {
            emojis.renameLocalCategory(from, to);
            return "redirect:" + PATH;
        } catch (InvalidCustomEmojiException e) {
            return refused(e, model, response);
        }
    }

    /**
     * The checkboxes are outside this form, since each emoji has a form of its own, so they name it with their
     * {@code form} attribute. IDs that don't parse are skipped, as removed ones are.
     */
    @PostMapping(PATH + "/move")
    String move(
            @RequestParam(required = false) List<String> ids,
            @RequestParam(required = false) String category,
            Model model,
            HttpServletResponse response) {
        try {
            var parsed = ids == null ? List.<UUID>of()
                    : ids.stream().map(Ids::parse).flatMap(Optional::stream).distinct().toList();
            if (parsed.isEmpty()) {
                throw new InvalidCustomEmojiException("Choose the emoji to move");
            }
            emojis.moveLocal(parsed, category);
            return "redirect:" + PATH;
        } catch (InvalidCustomEmojiException e) {
            return refused(e, model, response);
        }
    }

    /**
     * Shows what was imported on the page, rather than redirecting, so that the emoji that were skipped or failed can be
     * listed. Some failing doesn't fail the request, since the rest were imported.
     */
    @PostMapping(CustomEmojis.PACK_IMPORT_PATH)
    String importPack(
            @RequestParam(required = false) MultipartFile pack,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String replace,
            Model model,
            HttpServletResponse response) throws IOException {
        Path file = null;
        try {
            if (pack == null || pack.isEmpty()) {
                throw new InvalidCustomEmojiException("Choose a pack");
            }
            // Before it's read, since Spring takes files as large as a video.
            if (pack.getSize() > emojis.maxPackSize().toBytes()) {
                throw new InvalidCustomEmojiException("A pack can't be larger than %s. Import larger ones from the command line"
                        .formatted(Images.describe(emojis.maxPackSize())));
            }
            file = Files.createTempFile("elysium-emoji-pack-", ".upload");
            pack.transferTo(file);
            model.addAttribute("imported",
                    packs.importPack(file, pack.getOriginalFilename(), category, replace != null, true));
            model.addAttribute("shortcode", "");
            model.addAttribute("category", "");
            return page(model);
        } catch (InvalidCustomEmojiException e) {
            model.addAttribute("importCategory", category);
            model.addAttribute("importReplace", replace != null);
            return refused(e, model, response);
        } finally {
            if (file != null) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * One zip that both Misskey and Akkoma can import. It's written while the request waits, rather than as a
     * StreamingResponseBody, whose async timeout would cut off a large export partway.
     */
    @GetMapping(PATH + "/export")
    void exportPack(HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(packs.exportName()).build().toString());
        packs.export(response.getOutputStream());
    }

    /**
     * Someone else may have removed it already, which is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/delete")
    String remove(@PathVariable String id) {
        Ids.parse(id).ifPresent(emojis::deleteLocal);
        return "redirect:" + PATH;
    }

    private String refused(InvalidCustomEmojiException e, Model model, HttpServletResponse response) {
        response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
        model.addAttribute("error", e.getMessage());
        model.addAttribute("shortcode", "");
        model.addAttribute("category", "");
        return page(model);
    }

    private String page(Model model) {
        var groups = groups(emojis.listLocal().stream().map(emoji -> Row.from(emoji, instance)).toList());
        model.addAttribute("groups", groups);
        model.addAttribute("categories", groups.stream().map(Group::name).filter(Objects::nonNull).toList());
        model.addAttribute("maxCategoryLength", CustomEmojis.MAX_CATEGORY_LENGTH);
        model.addAttribute("uncategorized", CustomEmojis.UNCATEGORIZED);
        model.addAttribute("maxImageSize", emojis.describeMaxImageSize());
        model.addAttribute("maxPackSize", Images.describe(emojis.maxPackSize()));
        model.addAttribute("maxPackFiles", emojis.maxPackFiles());
        return "admin-custom-emojis";
    }

    /**
     * Categories in alphabetical order, ignoring case, and then the uncategorized emoji. Categories that differ only in
     * case stay apart, since apps show them apart.
     */
    static List<Group> groups(List<Row> rows) {
        var byCategory = new TreeMap<String, List<Row>>(
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())));
        for (var row : rows) {
            byCategory.computeIfAbsent(row.category(), category -> new ArrayList<>()).add(row);
        }
        return byCategory.entrySet().stream().map(entry -> new Group(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * @param name null for the uncategorized emoji
     */
    record Group(String name, List<Row> emojis) {
    }

    /**
     * @param category null for none
     */
    record Row(UUID id, String shortcode, String url, String category, boolean visibleInPicker) {

        static Row from(CustomEmoji emoji, InstanceProperties instance) {
            return new Row(emoji.id(), emoji.shortcode(), emoji.url(instance).toString(), emoji.category(),
                    emoji.visibleInPicker());
        }
    }
}
