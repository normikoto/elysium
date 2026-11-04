package dev.biribiri.elysium.ratelimit;

import dev.biribiri.elysium.actor.InstanceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Works out which client a request counts against.
 */
@Component
public class ClientAddresses {

    private static final Logger log = LoggerFactory.getLogger(ClientAddresses.class);

    private static final int IPV6_NETWORK_BYTES = 8;

    private final boolean publicBaseUrl;
    private final AtomicBoolean warnedAboutProxy = new AtomicBoolean();

    public ClientAddresses(InstanceProperties instance) {
        this.publicBaseUrl = !isLoopbackHost(instance.baseUrl().getHost());
    }

    /**
     * @return the key to count this request's client under
     */
    public String keyFor(HttpServletRequest request) {
        var remoteAddress = request.getRemoteAddr();
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(remoteAddress);
        } catch (IllegalArgumentException e) {
            // Not something that can be reduced to a network, so it's used as it is.
            return remoteAddress;
        }
        warnIfBehindMisconfiguredProxy(address);
        if (address instanceof Inet6Address) {
            // One host usually controls a whole /64, so counting each address separately wouldn't limit it.
            var network = Arrays.copyOf(address.getAddress(), IPV6_NETWORK_BYTES);
            return "ipv6:" + HexFormat.of().formatHex(network);
        }
        return address.getHostAddress();
    }

    /**
     * A public server shouldn't normally see clients on its own host or private network, so this probably means it's
     * seeing its proxy's address. Everyone would then share one limit, and one person could lock everyone out.
     *
     * <p>This can't detect a proxy that passes on an {@code X-Forwarded-For} header supplied by the client, because the
     * made-up addresses look like any other client's.
     */
    private void warnIfBehindMisconfiguredProxy(InetAddress address) {
        if (publicBaseUrl && isLocalNetwork(address) && warnedAboutProxy.compareAndSet(false, true)) {
            log.warn("""
                    Got a rate-limited request from the local address {}. If this server is behind a reverse proxy, \
                    every client probably shares one rate limit. Set server.forward-headers-strategy=native and make \
                    sure the proxy sends X-Forwarded-For. See docs/configuration.md.""", address.getHostAddress());
        }
    }

    /**
     * Whether the address is in a range that Tomcat trusts as a proxy by default.
     */
    private static boolean isLocalNetwork(InetAddress address) {
        var bytes = address.getAddress();
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                // Shared address space, which is 100.64.0.0/10. Overlay networks such as Tailscale use it.
                || (bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xc0) == 0x40)
                // Unique local addresses, which are fc00::/7. isSiteLocalAddress doesn't cover these.
                || (address instanceof Inet6Address && (bytes[0] & 0xfe) == 0xfc);
    }

    private static boolean isLoopbackHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        // URI keeps the brackets around IPv6 addresses.
        var literal = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
