package dev.biribiri.elysium.activitypub.http;

import java.net.UnknownHostException;

/**
 * A request was refused because of where it was going, and sending it again won't help. This extends
 * {@link UnknownHostException} because that's the only checked exception a DNS resolver can throw.
 */
public class BlockedTargetException extends UnknownHostException {

    BlockedTargetException(String message) {
        super(message);
    }
}
