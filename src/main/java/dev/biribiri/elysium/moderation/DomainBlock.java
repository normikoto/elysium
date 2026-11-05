package dev.biribiri.elysium.moderation;

import java.time.Instant;
import java.util.UUID;

/**
 * A server that this one has cut off, along with its subdomains.
 *
 * @param domain         the domain, in the form {@link DomainName#parse} gives
 * @param privateComment a note for admins, or null
 */
public record DomainBlock(UUID id, String domain, String privateComment, Instant createdAt) {
}
