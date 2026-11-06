package dev.biribiri.elysium.activitypub.vocab;

import java.net.URI;

/**
 * An actor's {@code endpoints}. Other servers store the shared inbox, so its URL can't change.
 *
 * @param sharedInbox where other servers can send one copy of an activity for every actor here
 */
public record Endpoints(URI sharedInbox) {
}
