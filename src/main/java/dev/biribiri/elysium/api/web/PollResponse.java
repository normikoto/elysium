package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.Poll;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Mastodon's Poll entity. Counts are hidden while a poll with hidden totals is open, as on Mastodon.
 */
@Schema(name = "Poll", description = """
        Mastodon's Poll entity. Its `id` is its status's. A poll from another server has the counts its server last \
        sent, plus votes from here since. `voters_count` is null unless more than one option can be chosen, and the \
        counts are null while a poll that hides its totals is open. `emojis` is always empty. `voted` and `own_votes` \
        are only there for a user.""")
record PollResponse(
        String id,
        @JsonProperty("expires_at") String expiresAt,
        boolean expired,
        boolean multiple,
        @JsonProperty("votes_count") int votesCount,
        @JsonProperty("voters_count") Integer votersCount,
        List<Option> options,
        List<Object> emojis,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean voted,
        @JsonProperty("own_votes") @JsonInclude(JsonInclude.Include.NON_NULL) List<Integer> ownVotes
) {

    @Schema(name = "PollOption")
    record Option(String title, @JsonProperty("votes_count") Integer votesCount) {
    }

    static PollResponse from(Poll poll, Instant now) {
        var hidden = poll.countsHidden(now);
        return new PollResponse(
                poll.statusId().toString(),
                poll.endsAt() == null ? null : ApiTimestamps.format(poll.endsAt()),
                poll.isExpired(now),
                poll.multiple(),
                poll.votesCount(),
                poll.multiple() ? poll.votersCount() : null,
                poll.options().stream()
                        .map(option -> new Option(option.title(), hidden ? null : option.votesCount()))
                        .toList(),
                List.of(),
                poll.ownVotes() == null ? null : !poll.ownVotes().isEmpty(),
                poll.ownVotes());
    }

    /**
     * The poll a version of a status had, which is only its options, as on Mastodon.
     */
    @Schema(name = "StatusEditPoll")
    record Version(List<VersionOption> options) {
    }

    @Schema(name = "StatusEditPollOption")
    record VersionOption(String title) {
    }
}
