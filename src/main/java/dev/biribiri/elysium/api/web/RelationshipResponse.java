package dev.biribiri.elysium.api.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.biribiri.elysium.graph.Relationship;

import java.util.List;

/**
 * Mastodon's Relationship entity. Only follows exist so far, so the rest hold the values they'd have for an account with no
 * blocks, mutes, notes, or endorsements.
 */
record RelationshipResponse(
        String id,
        boolean following,
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
                false,
                false,
                false,
                false,
                relationship.requested(),
                // Requests to follow local accounts are always accepted straight away.
                false,
                false,
                false,
                "");
    }
}
