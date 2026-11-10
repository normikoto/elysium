package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * What an edit changes about one of a status's attachments.
 *
 * @param description the new one, or null to keep it. Blank removes it
 * @param focus       the new one, or null to keep it
 */
public record MediaChange(UUID id, String description, Focus focus) {
}
