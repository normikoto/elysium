package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlockConflictException;
import dev.biribiri.elysium.moderation.DomainBlockPage;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.InvalidDomainBlockException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The admin page for blocking servers. Everything under {@code /admin} is limited to admins and needs a CSRF token, through
 * the security configuration.
 */
@Controller
class DomainBlockPagesController {

    private static final String PATH = "/admin/domain-blocks";

    private final DomainBlocks blocks;

    DomainBlockPagesController(DomainBlocks blocks) {
        this.blocks = blocks;
    }

    @GetMapping(PATH)
    String list(@RequestParam(name = "max_id", required = false) String maxId, Model model) {
        model.addAttribute("domain", "");
        model.addAttribute("comment", "");
        return page(Ids.parse(maxId).orElse(null), model);
    }

    @PostMapping(PATH)
    String add(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String comment,
            Model model,
            HttpServletResponse response) {
        try {
            blocks.create(domain, comment);
            return "redirect:" + PATH;
        } catch (InvalidDomainBlockException | DomainBlockConflictException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("domain", domain);
            model.addAttribute("comment", comment);
            return page(null, model);
        }
    }

    /**
     * Someone else may have removed it already, which is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/delete")
    String remove(@PathVariable String id) {
        Ids.parse(id).ifPresent(blocks::delete);
        return "redirect:" + PATH;
    }

    private String page(UUID maxId, Model model) {
        var page = new DomainBlockPage(DomainBlockPage.MAX_LIMIT, maxId, null, null);
        var found = blocks.list(page);
        model.addAttribute("blocks", found.stream().map(Row::from).toList());
        model.addAttribute("older", found.size() == page.limit() ? found.getLast().id() : null);
        model.addAttribute("maxCommentLength", DomainBlocks.MAX_COMMENT_LENGTH);
        return "admin-domain-blocks";
    }

    record Row(UUID id, String domain, String comment, String createdAt) {

        static Row from(DomainBlock block) {
            return new Row(
                    block.id(),
                    block.domain(),
                    block.privateComment(),
                    block.createdAt().truncatedTo(ChronoUnit.SECONDS).toString());
        }
    }
}
