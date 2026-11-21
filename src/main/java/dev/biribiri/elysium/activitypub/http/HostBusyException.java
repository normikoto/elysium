package dev.biribiri.elysium.activitypub.http;

import dev.biribiri.elysium.jobs.BusyException;

/**
 * A request wasn't started because {@link HostRequests} had no room for it. That says nothing about the other server, so
 * a job that gets this waits a moment and runs again, rather than failing and backing off as it would if the server were
 * down.
 */
public class HostBusyException extends BusyException {

    public HostBusyException(String host) {
        super("Too many fetches running for " + host);
    }
}
