package dev.biribiri.elysium.moderation;

import dev.biribiri.elysium.actor.Ids;
import dev.biribiri.elysium.moderation.internal.RuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * This server's rules, which admins write and clients show. They don't federate, since Mastodon only shows a server's
 * rules to its own visitors.
 */
@Service
public class Rules {

    /**
     * Mastodon's limit, which clients lay out for.
     */
    public static final int MAX_TEXT_LENGTH = 300;

    public static final int MAX_HINT_LENGTH = 2000;

    private final RuleRepository rules;

    Rules(RuleRepository rules) {
        this.rules = rules;
    }

    /**
     * @return every rule, in order
     */
    public List<Rule> list() {
        return rules.findAll();
    }

    /**
     * Adds a rule after the others.
     *
     * @param hint null or empty for none
     * @throws InvalidRuleException if the text is missing, or either is too long
     */
    public Rule create(String text, String hint) {
        var now = Instant.now();
        return rules.insertLast(Ids.timeOrdered(now), cleanText(text), cleanHint(hint), now);
    }

    /**
     * @param hint null or empty for none
     * @return the rule, if there is one with this ID
     * @throws InvalidRuleException as for {@link #create}
     */
    public Optional<Rule> update(UUID id, String text, String hint) {
        return rules.update(id, cleanText(text), cleanHint(hint), Instant.now());
    }

    /**
     * @return whether there was a rule with this ID
     */
    public boolean delete(UUID id) {
        return rules.delete(id);
    }

    /**
     * Swaps a rule with the one before or after it. The rules are renumbered as they go, so ties left by a race between
     * two admins are undone by the next move.
     *
     * @param earlier whether to move it up the list, rather than down
     */
    @Transactional
    public void move(UUID id, boolean earlier) {
        var all = new ArrayList<>(rules.findAllAndLock());
        var index = -1;
        for (var i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(id)) {
                index = i;
            }
        }
        var other = earlier ? index - 1 : index + 1;
        if (index < 0 || other < 0 || other >= all.size()) {
            return;
        }
        Collections.swap(all, index, other);
        for (var i = 0; i < all.size(); i++) {
            if (all.get(i).position() != i) {
                rules.updatePosition(all.get(i).id(), i);
            }
        }
    }

    private static String cleanText(String text) {
        var cleaned = text == null ? "" : text.strip();
        if (cleaned.isEmpty()) {
            throw new InvalidRuleException("A rule needs some text.");
        }
        if (cleaned.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRuleException("Rules can't be longer than %d characters.".formatted(MAX_TEXT_LENGTH));
        }
        if (!Comments.isStorable(cleaned)) {
            throw new InvalidRuleException("Rules can't contain invalid characters.");
        }
        return cleaned;
    }

    private static String cleanHint(String hint) {
        var cleaned = hint == null ? "" : hint.strip();
        if (cleaned.length() > MAX_HINT_LENGTH) {
            throw new InvalidRuleException("Hints can't be longer than %d characters.".formatted(MAX_HINT_LENGTH));
        }
        if (!Comments.isStorable(cleaned)) {
            throw new InvalidRuleException("Hints can't contain invalid characters.");
        }
        return cleaned;
    }
}
