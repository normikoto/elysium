package dev.biribiri.elysium.content;

import java.net.URI;
import java.util.Objects;

/**
 * An attachment of a status from another server, as its server described it. None of this has been checked against the
 * file, which isn't fetched here.
 *
 * @param url         where its server has it, an HTTP or HTTPS URL
 * @param type        what its server says it is
 * @param mediaType   the type its server gave, or null
 * @param description its {@code name}, which is HTML that hasn't been sanitized, or null
 * @param blurhash    or null
 * @param focus       or null
 */
public record RemoteAttachment(URI url, AttachmentType type, String mediaType, String description, Integer width,
                               Integer height, String blurhash, Focus focus) {

    public RemoteAttachment {
        Objects.requireNonNull(url);
        Objects.requireNonNull(type);
    }
}
