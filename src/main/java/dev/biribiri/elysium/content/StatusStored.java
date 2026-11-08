package dev.biribiri.elysium.content;

import java.util.UUID;

/** A successful local or remote insertion. Unlike StatusPublished, this must never trigger federation delivery. */
public record StatusStored(UUID statusId) {
}
