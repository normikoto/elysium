package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusQuote;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "InteractionRequest", description = """
        GoToSocial's InteractionRequest entity, for a quote. `accepted_at` and `rejected_at` are when the quote was last \
        decided, and both are missing while it's waiting. A rejected request that's approved later has only \
        `accepted_at`.""")
@JsonInclude(JsonInclude.Include.NON_NULL)
record InteractionRequestResponse(
        String id,
        @Schema(allowableValues = "quote") String type,
        @Schema(description = "When the quote was last asked for.") @JsonProperty("created_at") String createdAt,
        @Schema(description = "Who quoted the status.") AccountResponse account,
        @Schema(description = "The user's status that's quoted.") StatusResponse status,
        @Schema(description = "Not in GoToSocial. The quote.") StatusResponse quote,
        @JsonProperty("accepted_at") String acceptedAt,
        @JsonProperty("rejected_at") String rejectedAt
) {

    static InteractionRequestResponse of(
            StatusQuote quote, AccountResponse account, StatusResponse quoted, StatusResponse quoting) {
        var posted = Instant.parse(quoting.createdAt());
        var asked = quote.requestedAt() != null ? quote.requestedAt() : posted;
        // A quote decided before decisions were recorded only has when it was posted.
        var decided = ApiTimestamps.format(quote.decidedAt() != null ? quote.decidedAt() : asked);
        return new InteractionRequestResponse(
                quote.statusId().toString(),
                "quote",
                ApiTimestamps.format(asked),
                account,
                quoted,
                quoting,
                quote.state() == QuoteState.ACCEPTED ? decided : null,
                quote.state() == QuoteState.REJECTED || quote.state() == QuoteState.REVOKED ? decided : null);
    }
}
