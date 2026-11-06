package dev.biribiri.elysium.activitypub.inbound;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.apache.hc.client5.http.psl.PublicSuffixMatcher;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Limits the activities that each server can have stored, by count and by size, since one activity can be as large as
 * {@link Inbox#MAX_BODY_BYTES}. Without this, a server with valid signatures could keep adding rows until cleanup removes
 * them.
 *
 * <p>A server is its signer's registrable domain, such as {@code example.com} for {@code social.example.com}, from the
 * public suffix list. Otherwise anyone with a wildcard domain could get a new limit for each subdomain. Domains on the
 * list's private section, such as {@code github.io}, count each of their subdomains separately, since different people own
 * them. A host that isn't under a known suffix, or is an IP address, counts as itself. This goes by the signer rather than
 * the address, so that a large server's many delivery workers share one limit.
 *
 * <p>Counts are in memory, like the other limits, so they're lost on restart and each instance has its own.
 */
@Component
class ServerActivityLimits {

    private static final int MAX_TRACKED_SERVERS = 100_000;
    private static final PublicSuffixMatcher SUFFIXES = PublicSuffixMatcherLoader.getDefault();

    private final RateLimiter activities;
    private final RateLimiter bytes;
    private final List<String> exemptDomains;

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
     *
     * @return whether there was room for it
     */
    boolean tryAccept(URI signer, int bodyBytes) {
        var host = normalize(signer.getHost());
        if (isExempt(host)) {
            return true;
        }
        var key = serverKey(host);
        var activity = activities.tryAcquire(key);
        if (activity.isEmpty()) {
            return false;
        }
        if (bytes.tryAcquire(key, Math.max(1, bodyBytes)).isEmpty()) {
            activity.get().release();
            return false;
        }
        return true;
    }

    /**
     * When to suggest trying again. This is the longer of the two waits, so it can be later than needed if only the limit
     * on activities was in the way.
     */
    Duration timeUntilReset(URI signer) {
        var key = serverKey(normalize(signer.getHost()));
        var activityWait = activities.timeUntilReset(key);
        var bytesWait = bytes.timeUntilReset(key);
        return activityWait.compareTo(bytesWait) >= 0 ? activityWait : bytesWait;
    }

    /**
     * @param host a lowercase host, from {@link #normalize}
     * @return what to count the host's activities under
     */
    static String serverKey(String host) {
        if (isAddress(host)) {
            return host;
        }
        var root = SUFFIXES.getDomainRoot(host);
        return root == null ? host : root;
    }

    private boolean isExempt(String host) {
        return exemptDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    static String normalize(String host) {
        var lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static boolean isAddress(String host) {
        if (host.startsWith("[")) {
            return true;
        }
        try {
            InetAddress.ofLiteral(host);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
