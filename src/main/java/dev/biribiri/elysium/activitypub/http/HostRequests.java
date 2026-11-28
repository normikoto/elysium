package dev.biribiri.elysium.activitypub.http;

import dev.biribiri.elysium.ratelimit.RunningLimit;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counts the requests running to each host on this node, against the connections that {@link FederationHttpClient} allows to
 * one host.
 *
 * <p>Deliveries count every request and stop short of the limit, so they leave room for fetches, and hold back while fetches
 * are running. Fetches only count each other. A large fan-out to a server can last a long time, and if deliveries counted
 * against them, that server's deliveries here would be refused for as long as it lasted, whenever several of its keys had to be
 * fetched at once. So a fetch that starts during a fan-out can pass the limit, and wait a moment for a connection.
 *
 * <p>Every document fetch counts, whether of a key, an actor, or an object. A key lookup counts once, though it can take two
 * requests.
 */
@Component
public class HostRequests {

    public static final int MAX_DELIVERIES_PER_HOST = FederationHttpClient.MAX_CONNECTIONS_PER_HOST - 1;

    /**
     * Each waiting delivery holds one of the job threads, which every task shares, so a backlog for one host can't take them
     * all.
     */
    public static final int MAX_DELIVERIES_WAITING_PER_HOST = 12;

    /**
     * The same for every host together, since a few hosts with backlogs could otherwise hold every job thread between them,
     * leaving none for other tasks. It's well under the 64 threads that {@code db-scheduler.threads} gives by default.
     */
    public static final int MAX_DELIVERIES_WAITING = 24;

    private final RunningLimit fetches = new RunningLimit(FederationHttpClient.MAX_CONNECTIONS_PER_HOST);
    private final RunningLimit all = new RunningLimit(Integer.MAX_VALUE);
    private final RunningLimit waitingDeliveries = new RunningLimit(MAX_DELIVERIES_WAITING_PER_HOST);
    private final RunningLimit allWaitingDeliveries = new RunningLimit(MAX_DELIVERIES_WAITING);

    /**
     * @return whether there was room. If so, this needs a call to {@link #finishFetch} afterward.
     */
    public boolean tryStartFetch(String host) {
        if (!fetches.tryStart(host)) {
            return false;
        }
        all.tryStart(host);
        return true;
    }

    public void finishFetch(String host) {
        all.finish(host);
        fetches.finish(host);
    }

    /**
     * @return whether there was room. If so, this needs a call to {@link #finishDelivery} afterward.
     */
    public boolean tryStartDelivery(String host) {
        return all.tryStart(host, MAX_DELIVERIES_PER_HOST);
    }

    /**
     * Like {@link #tryStartDelivery(String)}, but waits for room for up to this long, unless too many deliveries are waiting
     * for this host, or for every host, already.
     */
    public boolean tryStartDelivery(String host, Duration wait) {
        if (tryStartDelivery(host)) {
            return true;
        }
        if (!allWaitingDeliveries.tryStart("")) {
            return false;
        }
        try {
            if (!waitingDeliveries.tryStart(host)) {
                return false;
            }
            try {
                return all.tryStart(host, MAX_DELIVERIES_PER_HOST, wait);
            } finally {
                waitingDeliveries.finish(host);
            }
        } finally {
            allWaitingDeliveries.finish("");
        }
    }

    public void finishDelivery(String host) {
        all.finish(host);
    }
}
