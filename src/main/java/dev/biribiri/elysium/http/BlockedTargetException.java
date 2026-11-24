package dev.biribiri.elysium.http;

import java.net.UnknownHostException;

/**
 * A request was refused because of where it was going, and sending it again won't help. This extends
 * {@link UnknownHostException} because that's the only checked exception a DNS resolver can throw.
 */
public class BlockedTargetException extends UnknownHostException {

    public BlockedTargetException(String message) {
        super(message);
    }
}
