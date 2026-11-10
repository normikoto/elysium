package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.UUID;

/**
 * Where to download one of another server's attachments from.
 *
 * @param authorId the account whose status it's attached to, which decides whose server it counts against
 */
public record RemoteMediaSource(UUID attachmentId, URI url, UUID authorId) {
}
