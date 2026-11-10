package dev.biribiri.elysium.moderation;

import java.util.UUID;

/**
 * A server's attachments are now rejected, without it being suspended, so the copies of them have to be removed.
 */
public record DomainMediaRejected(UUID blockId) {
}
