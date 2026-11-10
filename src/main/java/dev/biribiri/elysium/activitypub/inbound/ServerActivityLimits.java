package dev.biribiri.elysium.activitypub.inbound;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.moderation.DomainName;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Limits the activities that each server can have stored, by count and by size, since one activity can be as large as
 * {@link Inbox#MAX_BODY_BYTES}. Without this, a server with valid signatures could keep adding rows until cleanup removes
 * them.
 *
 * <p>A server is its signer's registrable domain, such as {@code example.com} for {@code social.example.com}, from the
 * public suffix list. Otherwise anyone with a wildcard domain could get a new limit for each subdomain. Domains on the
 * list's private section, such as {@code github.io}, count each of their subdomains separately, since different people own
 * them. A host that isn't under a known suffix counts as itself. An IP address counts the way
 * {@link ClientAddresses#keyFor(InetAddress)} does, so an IPv6 one by its /64. This goes by the signer rather than the
 * address, so that a large server's many delivery workers share one limit.
 *
 * <p>Counts are in memory, like the other limits, so they're lost on restart and each instance has its own.
 */
@Component
public class ServerActivityLimits {

    private static final int MAX_TRACKED_SERVERS = 100_000;
    private static final PublicSuffixMatcher SUFFIXES = PublicSuffixMatcherLoader.getDefault();

    private final RateLimiter activities;
    private final RateLimiter bytes;
    private final Set<String> exemptDomains;

    @Autowired
    ServerActivityLimits(InboxLimitProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    ServerActivityLimits(InboxLimitProperties properties, Ticker ticker) {
        this.activities = new RateLimiter(
                properties.activitiesPerMinute(), Duration.ofMinutes(1), MAX_TRACKED_SERVERS, ticker);
        this.bytes = new RateLimiter(
                properties.dataPerHour().toBytes(), Duration.ofHours(1), MAX_TRACKED_SERVERS, ticker);
        this.exemptDomains = properties.exemptDomains();
    }

    /**
     * Counts an activity against its signer's server, if there's room for it. One that's turned away doesn't count, so a
     * server that tries again isn't kept waiting longer.
     */
    Admission tryAdmit(URI signer, int bodyBytes) {
        if (isExempt(signer.getHost())) {
            return new Admission.Admitted(() -> {
            });
        }
        var key = serverKey(signer);
        var activity = activities.tryAcquire(key);
        if (activity.isEmpty()) {
            return new Admission.Refused(activities.timeUntilReset(key));
        }
        var data = bytes.tryAcquire(key, Math.max(1, bodyBytes));
        if (data.isEmpty()) {
            activity.get().release();
            return new Admission.Refused(bytes.timeUntilReset(key));
        }
        return new Admission.Admitted(() -> {
            activity.get().release();
            data.get().release();
        });
    }

    /**
     * What counts against a server's limits.
     */
    public static String serverKey(URI signer) {
        var host = signer.getHost();
        if (host == null) {
            // Such as a name with an underscore, which URI only reads as an authority. It can still count as itself.
            var authority = signer.getRawAuthority();
            return authority == null ? signer.toString() : authority.toLowerCase(Locale.ROOT);
        }
        var address = address(host);
        if (address != null) {
            return ClientAddresses.keyFor(address);
        }
        var name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        var root = SUFFIXES.getDomainRoot(name);
        return root == null ? name : root;
    }

    /**
     * @param domain a domain in {@link DomainName}'s form
     * @return whether anyone can register a domain under this one, such as {@code co.uk} or {@code github.io}
     */
    static boolean isPublicSuffix(String domain) {
        return SUFFIXES.getDomainRoot(domain) == null;
    }

    private boolean isExempt(String host) {
        return DomainName.normalizeHost(host)
                .map(name -> DomainName.suffixes(name).stream().anyMatch(exemptDomains::contains))
                .orElse(false);
    }

    /**
     * @return the address, or null if the host isn't one
     */
    private static InetAddress address(String host) {
        // URI keeps the brackets around IPv6 addresses.
        var literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    sealed interface Admission {

        /**
         * @param release gives back what this activity counted for, for when it couldn't be stored after all
         */
        record Admitted(Runnable release) implements Admission {
        }

        /**
         * @param retryAfter how long until the limit that turned it away has room again
         */
        record Refused(Duration retryAfter) implements Admission {
        }
    }
}
