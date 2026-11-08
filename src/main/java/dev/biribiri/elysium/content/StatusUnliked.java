package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.UUID;

/** The activity being withdrawn, retained after its current-like row has been removed. */
public record StatusUnliked(UUID actorId, UUID statusId, UUID authorId, URI statusUri, String activityUri) {
}
