package dev.biribiri.elysium.moderation;

/**
 * A server's attachments aren't rejected any more, because its block was changed or removed.
 *
 * @param domain the domain the block was on, which is all that's left of a removed one
 */
public record DomainMediaAllowed(String domain) {
}
