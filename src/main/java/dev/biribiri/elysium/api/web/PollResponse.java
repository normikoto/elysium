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
        Mastodon's Poll entity. `id` matches the status ID. A remote poll includes the latest counts it received \
        from its server plus newer local votes.

        `voters_count` is null for single-choice polls. Option counts are null while a poll with hidden totals is \
        open. Only user requests get `voted` and `own_votes`.""")
record PollResponse(
        String id,
        @JsonProperty("expires_at") String expiresAt,
        boolean expired,
        boolean multiple,
        @JsonProperty("votes_count") int votesCount,
        @JsonProperty("voters_count") Integer votersCount,
        List<Option> options,
        List<CustomEmojiResponse> emojis,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean voted,
        @JsonProperty("own_votes") @JsonInclude(JsonInclude.Include.NON_NULL) List<Integer> ownVotes
) {

    @Schema(name = "PollOption")
    record Option(String title, @JsonProperty("votes_count") Integer votesCount) {
    }

    /**
     * @param emojis its status's custom emoji, of which it has those its options name
     */
    static PollResponse from(Poll poll, Instant now, List<CustomEmojiResponse> emojis) {
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
                CustomEmojiResponse.namedIn(emojis, poll.options().stream().map(Poll.Option::title).toList()),
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
