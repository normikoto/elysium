package dev.biribiri.elysium.actor;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored remote profile has links whose pages still need to be checked for a backlink.
 */
public record RemoteProfileLinksToVerify(UUID actorId, Instant at) {
}
