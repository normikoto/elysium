package dev.biribiri.elysium.timeline;

import java.time.Instant;
import java.util.UUID;

public record Marker(UUID lastReadId, int version, Instant updatedAt) {
}
