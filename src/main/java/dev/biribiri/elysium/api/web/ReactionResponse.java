package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.ReactionSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pleroma's EmojiReaction entity.
 *
 * @param accounts the most recent accounts that reacted with it, only when they're listed
 */
@Schema(name = "EmojiReaction", description = """
        Pleroma's EmojiReaction entity: everyone's reactions to a status with one emoji. `name` is the Unicode emoji, or a \
        custom emoji's shortcode, followed by `@` and its server's domain for one from another server. A custom emoji has \
        `url` and `static_url`. `accounts` is only in a listing of a status's reactions.""")
@JsonInclude(JsonInclude.Include.NON_NULL)
record ReactionResponse(
        String name,
        long count,
        boolean me,
        String url,
        @JsonProperty("static_url") String staticUrl,
        List<AccountResponse> accounts
) {

    static ReactionResponse of(ReactionSummary summary, InstanceProperties instance, List<AccountResponse> accounts) {
        var emoji = summary.emoji();
        return new ReactionResponse(
                summary.name(),
                summary.count(),
                summary.me(),
                emoji == null ? null : emoji.url(instance).toString(),
                emoji == null ? null : emoji.staticUrl(instance).toString(),
                accounts);
    }

    /**
     * Where Pleroma's extensions to a status go.
     */
    @Schema(name = "PleromaStatus")
    record Pleroma(@JsonProperty("emoji_reactions") List<ReactionResponse> emojiReactions) {
    }
}
