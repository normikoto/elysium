package dev.biribiri.elysium.moderation;

/**
 * The domain is already blocked, by a block on it or on a domain above it.
 */
public class DomainBlockConflictException extends RuntimeException {

    private final transient DomainBlock existing;

    DomainBlockConflictException(DomainBlock existing) {
        super(existing.domain() + " is already blocked.");
        this.existing = existing;
    }

    public DomainBlock existing() {
        return existing;
    }
}
