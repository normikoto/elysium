package dev.biribiri.elysium.activitypub.http;

import dev.biribiri.elysium.ratelimit.RunningLimit;
import org.springframework.stereotype.Component;

/**
 * Counts the requests running to each host on this node, so that key fetches and deliveries together stay within the
 * connections that {@link FederationHttpClient} allows to one host. Any more would only wait for a connection, and could time
 * out doing so.
 *
 * <p>Deliveries can't take the last connection. A large fan-out to a server can last a long time, and without one left over,
 * that server's own deliveries here would be refused for as long as it lasted, whenever their keys had to be fetched.
 *
 * <p>Fetches of actor documents aren't counted, so they can still wait for a connection.
 */
@Component
public class HostRequests {

    public static final int MAX_DELIVERIES_PER_HOST = FederationHttpClient.MAX_CONNECTIONS_PER_HOST - 1;

    private final RunningLimit running = new RunningLimit(FederationHttpClient.MAX_CONNECTIONS_PER_HOST);

    /**
     * @return whether there was room. If so, this needs a call to {@link #finish} afterward.
     */
    public boolean tryStartFetch(String host) {
        return running.tryStart(host);
    }

    /**
     * @return whether there was room. If so, this needs a call to {@link #finish} afterward.
     */
    public boolean tryStartDelivery(String host) {
        return running.tryStart(host, MAX_DELIVERIES_PER_HOST);
    }

    public void finish(String host) {
        running.finish(host);
    }
}
