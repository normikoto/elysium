package dev.biribiri.elysium.activitypub.http;

/**
 * A request wasn't started because {@link HostRequests} had no room for it. That says nothing about the other server, so
 * a job that gets this waits a moment and runs again, rather than failing and backing off as it would if the server were
 * down.
 *
 * <p>It's unchecked so that it can pass through the callers between a fetch and the job, which turn other fetch failures
 * into their own answers, such as using an older copy of an actor.
 */
public class HostBusyException extends RuntimeException {

    public HostBusyException(String host) {
        super("Too many fetches running for " + host);
    }
}
