package dev.biribiri.elysium.activitypub.http;

import org.apache.hc.client5.http.DnsResolver;
import org.springframework.boot.http.client.InetAddressFilter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Stops requests from reaching private or internal addresses, since other servers choose the URLs this server fetches.
 *
 * <p>This is a resolver rather than a check on the URL because the client connects to exactly the addresses returned
 * here, without looking them up again, so a host can't pass the check and then change its DNS answer. The client also
 * sends IP literals through here, including forms like {@code 2130706433} and {@code 127.1}, so only the resolved
 * addresses are checked and never the hostname.
 */
final class PublicAddressDnsResolver implements DnsResolver {

    /**
     * The exclusion covers IPv4-compatible addresses such as {@code ::10.0.0.1}, which the filter otherwise allows. It
     * can't overlap with real addresses, because that range is deprecated.
     */
    static final InetAddressFilter PUBLIC_ADDRESSES = InetAddressFilter.externalAddresses().andNot("::/96");

    private final DnsResolver delegate;
    private final InetAddressFilter filter;

    PublicAddressDnsResolver(DnsResolver delegate, InetAddressFilter filter) {
        this.delegate = delegate;
        this.filter = filter;
    }

    /**
     * Drops the addresses that aren't allowed, so a host with both kinds is only reached on the allowed ones.
     *
     * @throws BlockedTargetException if that leaves none
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        var allowed = Arrays.stream(delegate.resolve(host)).filter(filter::matches).toArray(InetAddress[]::new);
        if (allowed.length == 0) {
            throw new BlockedTargetException("No public address for " + host);
        }
        return allowed;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }
}
