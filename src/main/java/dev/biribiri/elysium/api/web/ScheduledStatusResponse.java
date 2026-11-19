package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.ScheduledStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * Mastodon's ScheduledStatus entity.
 */
@Schema(name = "ScheduledStatus", description = """
        Mastodon's ScheduledStatus entity. `params` has what the status is to be posted with, as the request gave it. \
        `idempotency`, `scheduled_at`, and `application_id` in it are always null.""")
record ScheduledStatusResponse(
        String id,
        @JsonProperty("scheduled_at") String scheduledAt,
        Params params,
        @JsonProperty("media_attachments") List<MediaAttachmentResponse> mediaAttachments
) {

    @Schema(name = "ScheduledStatusParams")
    record Params(
            String text,
            PollParams poll,
            @JsonProperty("media_ids") List<String> mediaIds,
            Boolean sensitive,
            @JsonProperty("spoiler_text") String spoilerText,
            String visibility,
            @JsonProperty("in_reply_to_id") String inReplyToId,
            String language,
            @JsonProperty("application_id") String applicationId,
            @JsonProperty("scheduled_at") String scheduledAt,
            String idempotency,
            @JsonProperty("with_rate_limit") boolean withRateLimit,
            @JsonProperty("quoted_status_id") String quotedStatusId,
            @JsonProperty("quote_approval_policy") String quoteApprovalPolicy,
            @JsonProperty("local_only") boolean localOnly,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("quote_review") String quoteReview
    ) {
    }

    @Schema(name = "ScheduledStatusPoll")
    record PollParams(
            List<String> options,
            @JsonProperty("expires_in") String expiresIn,
            boolean multiple,
            @JsonProperty("hide_totals") boolean hideTotals
    ) {

        static PollParams from(NewPoll poll) {
            return poll == null ? null
                    : new PollParams(poll.options(), Long.toString(poll.expiresIn()), poll.multiple(), poll.hideTotals());
        }
    }

    static ScheduledStatusResponse from(ScheduledStatus scheduled, InstanceProperties instance) {
        var draft = scheduled.draft();
        var params = new Params(
                draft.text(),
                PollParams.from(draft.poll()),
                draft.mediaIds().isEmpty() ? null : draft.mediaIds().stream().map(UUID::toString).toList(),
                draft.sensitive(),
                draft.spoilerText(),
                draft.visibility().value(),
                draft.inReplyToId() == null ? null : draft.inReplyToId().toString(),
                draft.language(),
                null,
                null,
                null,
                false,
                draft.quotedStatusId() == null ? null : draft.quotedStatusId().toString(),
                draft.quotePolicy() == null ? null : draft.quotePolicy().value(),
                draft.localOnly(),
                draft.contentType().value(),
                draft.quoteReview() == null ? null : draft.quoteReview().value());
        return new ScheduledStatusResponse(
                scheduled.id().toString(),
                ApiTimestamps.format(scheduled.scheduledAt()),
                params,
                scheduled.media().stream().map(attachment -> MediaAttachmentResponse.from(attachment, instance)).toList());
    }
}
