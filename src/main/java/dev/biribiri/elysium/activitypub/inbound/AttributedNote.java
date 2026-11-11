package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;

/**
 * A note fetched on another server's word, such as for a boost or a quote of it, whose author can be trusted because only
 * the note's own server can say who wrote it.
 *
 * @param author its only {@code attributedTo}, on the note's server
 */
record AttributedNote(RemoteNote note, URI author) {

    private static final Logger log = LoggerFactory.getLogger(AttributedNote.class);

    /**
     * @return the note, or empty if what's served there isn't a {@code Note} with this ID and one author on its server
     * @throws IllegalStateException if the note's server couldn't be reached, which is worth trying again
     */
    static Optional<AttributedNote> fetch(URI id, ActivityDocuments documents) {
        Optional<RemoteNote> read;
        try {
            read = RemoteNote.read(documents.fetch(id)).filter(note -> note.id().equals(id));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Ignoring note {}, which couldn't be fetched: {}", id, e.getMessage());
            return Optional.empty();
        }
        var attributed = read.flatMap(note -> note.attributedTo().size() == 1
                        ? ActivityObject.parseUri(note.attributedTo().getFirst())
                        : Optional.<URI>empty())
                .filter(author -> Inbox.isOnActorsServer(id, author));
        if (read.isPresent() && attributed.isEmpty()) {
            log.debug("Ignoring note {}, which isn't attributed to one actor on its server", id);
        }
        return attributed.map(author -> new AttributedNote(read.get(), author));
    }
}
