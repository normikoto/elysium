package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.moderation.Rule;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Rule", description = "Mastodon's Rule entity, with no `translations`.")
record RuleResponse(
        String id,
        String text,
        @Schema(description = "Plain text that explains the rule, empty for none.") String hint) {

    static RuleResponse from(Rule rule) {
        return new RuleResponse(rule.id().toString(), rule.text(), rule.hint());
    }
}
