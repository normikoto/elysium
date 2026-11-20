package dev.biribiri.elysium.moderation.internal;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.moderation.InvalidRuleException;
import dev.biribiri.elysium.moderation.Rules;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * The admin page for the server's rules. Everything under {@code /admin} is limited to admins and needs a CSRF token,
 * through the security configuration.
 */
@Controller
class RulePagesController {

    private static final String PATH = "/admin/rules";

    private final Rules rules;

    RulePagesController(Rules rules) {
        this.rules = rules;
    }

    @GetMapping(PATH)
    String list(Model model) {
        model.addAttribute("text", "");
        model.addAttribute("hint", "");
        return page(model);
    }

    @PostMapping(PATH)
    String add(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String hint,
            Model model,
            HttpServletResponse response) {
        try {
            rules.create(text, hint);
            return "redirect:" + PATH;
        } catch (InvalidRuleException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("text", text);
            model.addAttribute("hint", hint);
            return page(model);
        }
    }

    /**
     * A rule that someone else has removed meanwhile is left removed. An edit that can't be saved shows the page with
     * the rule as it was, since the form for each rule is filled from what's stored.
     */
    @PostMapping(PATH + "/{id}")
    String change(
            @PathVariable String id,
            @RequestParam(required = false) String text,
            @RequestParam(required = false) String hint,
            Model model,
            HttpServletResponse response) {
        try {
            Ids.parse(id).ifPresent(ruleId -> rules.update(ruleId, text, hint));
            return "redirect:" + PATH;
        } catch (InvalidRuleException e) {
            response.setStatus(HttpStatus.UNPROCESSABLE_CONTENT.value());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("text", "");
            model.addAttribute("hint", "");
            return page(model);
        }
    }

    /**
     * Anything but {@code up} moves it down.
     */
    @PostMapping(PATH + "/{id}/move")
    String move(@PathVariable String id, @RequestParam(required = false) String direction) {
        Ids.parse(id).ifPresent(ruleId -> rules.move(ruleId, "up".equals(direction)));
        return "redirect:" + PATH;
    }

    /**
     * Someone else may have removed it already, which is the outcome that was asked for.
     */
    @PostMapping(PATH + "/{id}/delete")
    String remove(@PathVariable String id) {
        Ids.parse(id).ifPresent(rules::delete);
        return "redirect:" + PATH;
    }

    private String page(Model model) {
        model.addAttribute("rules",
                rules.list().stream().map(rule -> new Row(rule.id(), rule.text(), rule.hint())).toList());
        model.addAttribute("maxTextLength", Rules.MAX_TEXT_LENGTH);
        model.addAttribute("maxHintLength", Rules.MAX_HINT_LENGTH);
        return "admin-rules";
    }

    record Row(UUID id, String text, String hint) {
    }
}
