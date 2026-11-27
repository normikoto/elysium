package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.TagDay;
import dev.biribiri.elysium.text.Hashtags;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Mastodon's Tag entity.
 *
 * @param following whether the viewer follows the tag, or null when there's no user, as Mastodon leaves it out
 * @param featuring whether the viewer features the tag on their profile, or null when there's no user
 */
@Schema(name = "Tag", description = """
        Mastodon's Tag entity. `name` is normalized: lowercase, and in Unicode's NFKC form. `history` only counts \
        public statuses stored on this server. `following` and `featuring` are omitted when there's no user token.""")
record TagResponse(
        String name,
        String url,
        List<History> history,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean following,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean featuring) {

    /**
     * @param day      the start of the day in UTC, as seconds since the epoch, which Mastodon sends as a string
     * @param uses     how many statuses used the tag, as a string, as Mastodon sends it
     * @param accounts how many accounts posted them, as a string
     */
    @Schema(name = "TagHistory", description = "One day of a tag's use, newest first. Every value is a string, as on Mastodon.")
    record History(String day, String uses, String accounts) {

        static History from(TagDay day) {
            return of(day.day(), day.uses(), day.accounts());
        }

        static History of(LocalDate day, long uses, long accounts) {
            return new History(
                    Long.toString(day.atStartOfDay(ZoneOffset.UTC).toEpochSecond()),
                    Long.toString(uses),
                    Long.toString(accounts));
        }
    }

    static TagResponse of(String name, List<History> history, Boolean following, Boolean featuring,
                          InstanceProperties instance) {
        return new TagResponse(
                name,
                Hashtags.url(instance.baseUrl(), name).toString(),
                history,
                following,
                featuring);
    }
}
