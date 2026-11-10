package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * Attachments of a status from another server were stored, as links to that server's copies, to be downloaded.
 */
public record RemoteMediaAdded(List<UUID> attachmentIds) {

    public RemoteMediaAdded {
        attachmentIds = List.copyOf(attachmentIds);
    }
}
