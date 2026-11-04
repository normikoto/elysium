package dev.biribiri.elysium.content;

import java.net.URI;
import java.time.Instant;

/**
 * What's kept of a deleted status that anyone could see.
 */
public record DeletedStatus(URI uri, Instant deletedAt) {
}
