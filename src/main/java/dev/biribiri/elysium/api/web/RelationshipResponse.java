package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.graph.Relationship;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Mastodon's Relationship entity. Notes, endorsements, and notifications of new statuses don't exist, so those fields hold
 * the values they'd have for an account with none.
 */
@Schema(name = "Relationship", description = """
        Mastodon's Relationship entity. Notes, endorsements, and notifications of new statuses don't exist, so `note`, \
        `endorsed`, `notifying`, and `languages` are always empty, false, or null.""")
record RelationshipResponse(
        String id,
        boolean following,
        @Schema(description = "True whenever `following` or `requested` is.")
        @JsonProperty("showing_reblogs") boolean showingReblogs,
        boolean notifying,
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
        String note
) {

    static RelationshipResponse from(Relationship relationship) {
        var followingOrRequested = relationship.following() || relationship.requested();
        return new RelationshipResponse(
                relationship.otherId().toString(),
                relationship.following(),
                followingOrRequested,
                false,
                null,
                relationship.followedBy(),
                relationship.blocking(),
                relationship.blockedBy(),
                relationship.muting(),
                relationship.mutingNotifications(),
                relationship.requested(),
                relationship.requestedBy(),
                relationship.domainBlocking(),
                false,
                "");
    }
}
