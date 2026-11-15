package dev.biribiri.elysium.content;

import java.util.UUID;

/**
 * A remote status or boost was removed from this server, because its author's server said so or an admin removed it.
 * Unlike {@link StatusDeleted}, nothing is sent to other servers for this.
 *
 * @param statusId the status's ID here, which no longer finds anything
 */
public record RemoteStatusDeleted(UUID statusId) {
}
