package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Conversation", description = "A direct-message thread for one participant set. Its ID is distinct from its pagination cursors.")
record ConversationResponse(String id, boolean unread, List<AccountResponse> accounts,
                            @JsonProperty("last_status") StatusResponse lastStatus) {
}
