package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.graph.Relationship;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Relationship", description = "Mastodon's Relationship entity.")
record RelationshipResponse(
        String id,
        boolean following,
        @Schema(description = "Whether the user's follow or request lets the account's boosts into the user's feeds.")
        @JsonProperty("showing_reblogs") boolean showingReblogs,
        @Schema(description = "Whether the user's follow or request notifies the user of the account's new statuses.")
        boolean notifying,
        @Schema(description = """
                The only languages whose statuses by the account reach the user's feeds, or null for every language.""")
        List<String> languages,
        @JsonProperty("followed_by") boolean followedBy,
        boolean blocking,
        @JsonProperty("blocked_by") boolean blockedBy,
        boolean muting,
        @JsonProperty("muting_notifications") boolean mutingNotifications,
        boolean requested,
        @JsonProperty("requested_by") boolean requestedBy,
        @JsonProperty("domain_blocking") boolean domainBlocking,
        boolean endorsed,
        @Schema(description = "The user's private note about the account, as plain text, or empty if there's none.")
        String note
) {

    static RelationshipResponse from(Relationship relationship) {
        return new RelationshipResponse(
                relationship.otherId().toString(),
                relationship.following(),
                relationship.showingReblogs(),
                relationship.notifying(),
                relationship.languages().isEmpty() ? null : relationship.languages(),
                relationship.followedBy(),
                relationship.blocking(),
                relationship.blockedBy(),
                relationship.muting(),
                relationship.mutingNotifications(),
                relationship.requested(),
                relationship.requestedBy(),
                relationship.domainBlocking(),
                relationship.endorsed(),
                relationship.memo());
    }
}
