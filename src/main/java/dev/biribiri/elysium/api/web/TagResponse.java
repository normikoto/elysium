package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.TagDay;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Tag entity.
 *
 * @param following whether the viewer follows the tag, which nobody can yet, or null when there's no viewer, as Mastodon
 *                  leaves it out
 */
@Schema(name = "Tag", description = """
        Mastodon's Tag entity. `name` is normalized: lowercase, and in Unicode's NFKC form. `history` only counts public \
        statuses stored on this server. Tags can't be followed or featured, so `following` is always false, and is left \
        out when there's no token.""")
record TagResponse(
        String name,
        String url,
        List<History> history,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean following) {

    /**
     * @param day      the start of the day in UTC, as seconds since the epoch, which Mastodon sends as a string
     * @param uses     how many statuses used the tag, as a string, as Mastodon sends it
     * @param accounts how many accounts posted them, as a string
     */
    @Schema(name = "TagHistory", description = "One day of a tag's use, newest first. Every value is a string, as on Mastodon.")
    record History(String day, String uses, String accounts) {

        static History from(TagDay day) {
            return new History(
                    Long.toString(day.day().atStartOfDay(ZoneOffset.UTC).toEpochSecond()),
                    Long.toString(day.uses()),
                    Long.toString(day.accounts()));
        }
    }

    static TagResponse of(String name, List<TagDay> days, boolean signedIn, InstanceProperties instance) {
        return new TagResponse(
                name,
                Hashtags.url(instance.baseUrl(), name).toString(),
                days.stream().map(History::from).toList(),
                signedIn ? false : null);
    }
}
