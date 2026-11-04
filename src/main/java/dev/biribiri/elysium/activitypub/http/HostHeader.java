package dev.biribiri.elysium.activitypub.http;

import java.util.Locale;

/**
 * The form of the {@code Host} header. Signatures cover this header, so what's signed and what's sent both have to come
 * from here. If the two differed, this server couldn't notice: only the server receiving the request would reject it.
 */
public final class HostHeader {

    private HostHeader() {
    }

    /**
     * @return the host in lowercase, plus the port if it isn't the scheme's default
     */
    public static String value(String scheme, String host, int port) {
        var sentPort = port(scheme, port);
        return sentPort == -1 ? host(host) : host(host) + ":" + sentPort;
    }

    static String host(String host) {
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the port to send, or -1 if it should be left out
     */
    static int port(String scheme, int port) {
        return port == (scheme.equalsIgnoreCase("https") ? 443 : 80) ? -1 : port;
    }
}
