package dev.biribiri.elysium.inbox;

import java.time.Instant;
import java.util.UUID;

public record Marker(UUID lastReadId, int version, Instant updatedAt) {
}
