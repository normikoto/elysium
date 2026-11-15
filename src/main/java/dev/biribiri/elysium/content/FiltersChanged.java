package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A local account's filters changed, so its clients should load them again.
 */
public record FiltersChanged(UUID accountId) {
}
