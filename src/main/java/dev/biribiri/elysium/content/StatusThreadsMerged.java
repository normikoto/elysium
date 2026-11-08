package dev.biribiri.elysium.content;

import java.util.UUID;

/** A previously missing parent connected two stored parts of a thread. */
public record StatusThreadsMerged(UUID previousId, UUID threadId) {
}
