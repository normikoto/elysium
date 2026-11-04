package dev.biribiri.elysium.activitypub.http;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Set;

/**
 * Lets tests in other packages use a real client against a server on the loopback address, for any hostname.
 */
public final class LoopbackClients {

    private LoopbackClients() {
    }

    public static FederationHttpClient create() {
        return new FederationHttpClient(new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return new InetAddress[]{InetAddress.getLoopbackAddress()};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        }, Set.of("http"), Duration.ofSeconds(30), "Elysium/test");
    }
}
