package dev.biribiri.elysium.actor;

import java.util.UUID;

/**
 * An account on another server has an avatar or header that isn't kept here yet, so it can be downloaded.
 *
 * @param avatar whether its avatar needs downloading
 * @param header whether its header needs downloading
 */
public record RemoteProfileImageAdded(UUID actorId, boolean avatar, boolean header) {
}
