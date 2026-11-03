package dev.biribiri.elysium.auth.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.InstanceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limits login and sign-up attempts, which are expensive because each one hashes a password.
 *
 * <p>An attempt takes its slots before hashing and gets them back afterwards if it shouldn't count. Counting failures
 * only after hashing would let a burst of concurrent requests all pass the check before any failure was recorded.
 *
 * <p>The limit on one username from one address is the strict one. The limit on a username from anywhere is much looser,
 * so that it takes a lot of effort for someone else to lock an account out.
 */
@Component
class AuthRateLimits {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimits.class);

    private static final int MAX_TRACKED_KEYS = 100_000;
    private static final int IPV6_NETWORK_BYTES = 8;
    private static final String USERNAME_PARAMETER = "username";

    private final boolean enabled;
    private final boolean publicBaseUrl;
    private final RateLimiter loginsByAddress;
    private final RateLimiter loginsByUsernameAndAddress;
    private final RateLimiter loginsByUsername;
    private final RateLimiter signUpsByAddress;
    private final AtomicBoolean warnedAboutProxy = new AtomicBoolean();

    @Autowired
    AuthRateLimits(RateLimitProperties properties, InstanceProperties instance) {
        this(properties, instance, Ticker.systemTicker());
    }

    AuthRateLimits(RateLimitProperties properties, InstanceProperties instance, Ticker ticker) {
        this.enabled = properties.enabled();
        this.publicBaseUrl = !isLoopbackHost(instance.baseUrl().getHost());
        this.loginsByAddress = new RateLimiter(20, Duration.ofMinutes(15), MAX_TRACKED_KEYS, ticker);
        this.loginsByUsernameAndAddress = new RateLimiter(5, Duration.ofMinutes(15), MAX_TRACKED_KEYS, ticker);
        this.loginsByUsername = new RateLimiter(50, Duration.ofHours(1), MAX_TRACKED_KEYS, ticker);
        this.signUpsByAddress = new RateLimiter(5, Duration.ofHours(1), MAX_TRACKED_KEYS, ticker);
    }

    /**
     * Starts a login attempt. If it's allowed, it counts as a failure until {@link #loginSucceeded} or
     * {@link #loginNotCounted} says otherwise.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginLogin(HttpServletRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        var address = clientAddress(request);
        // This comes first so that one address can't push other counts out of the caches with made-up usernames.
        if (!loginsByAddress.tryAcquire(address)) {
            return Optional.of(loginsByAddress.timeUntilReset(address));
        }
        var username = loginUsername(request);
        if (username.isEmpty()) {
            return Optional.empty();
        }

        var pair = pairKey(username.get(), address);
        if (!loginsByUsernameAndAddress.tryAcquire(pair)) {
            loginsByAddress.release(address);
            return Optional.of(loginsByUsernameAndAddress.timeUntilReset(pair));
        }
        if (!loginsByUsername.tryAcquire(username.get())) {
            loginsByUsernameAndAddress.release(pair);
            loginsByAddress.release(address);
            return Optional.of(loginsByUsername.timeUntilReset(username.get()));
        }
        return Optional.empty();
    }

    /**
     * Records a successful login. This doesn't erase the earlier failures from the address or against the username, so
     * logging in to your own account doesn't help with guessing someone else's password.
     */
    void loginSucceeded(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        var address = clientAddress(request);
        loginsByAddress.release(address);
        loginUsername(request).ifPresent(username -> {
            loginsByUsernameAndAddress.reset(pairKey(username, address));
            loginsByUsername.release(username);
        });
    }

    /**
     * Gives back the slots of a login that failed for a reason other than its credentials, such as a busy server.
     */
    void loginNotCounted(HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        var address = clientAddress(request);
        loginsByAddress.release(address);
        loginUsername(request).ifPresent(username -> {
            loginsByUsernameAndAddress.release(pairKey(username, address));
            loginsByUsername.release(username);
        });
    }

    /**
     * Starts a sign-up attempt, which counts whether or not it creates an account. This also limits using sign-ups to
     * find out which usernames exist.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginSignUp(HttpServletRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        var address = clientAddress(request);
        if (signUpsByAddress.tryAcquire(address)) {
            return Optional.empty();
        }
        return Optional.of(signUpsByAddress.timeUntilReset(address));
    }

    /**
     * Gives back the slot of a sign-up that failed because of the server, such as when it's busy.
     */
    void signUpNotCounted(HttpServletRequest request) {
        if (enabled) {
            signUpsByAddress.release(clientAddress(request));
        }
    }

    /**
     * The value for a {@code Retry-After} header, which is in whole seconds and shouldn't tell clients to retry early.
     */
    static String retryAfterSeconds(Duration wait) {
        return Long.toString(Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0)));
    }

    /**
     * Usernames that can't belong to an account have nothing to protect, so only the address's limit applies. Leaving
     * them out also keeps strings as long as the largest allowed request out of the caches.
     */
    private static Optional<String> loginUsername(HttpServletRequest request) {
        var username = request.getParameter(USERNAME_PARAMETER);
        if (username == null || !Actor.isValidLocalUsername(username)) {
            return Optional.empty();
        }
        return Optional.of(username.toLowerCase(Locale.ROOT));
    }

    private static String pairKey(String username, String address) {
        // Usernames can't contain this separator.
        return username + "|" + address;
    }

    private String clientAddress(HttpServletRequest request) {
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
     */
    private void warnIfBehindMisconfiguredProxy(InetAddress address) {
        if (publicBaseUrl && isLocalNetwork(address) && warnedAboutProxy.compareAndSet(false, true)) {
            log.warn("""
                    Got a login or sign-up attempt from the local address {}. If this server is behind a reverse proxy, \
                    every client probably shares one rate limit. Set server.forward-headers-strategy=native and make \
                    sure the proxy sends X-Forwarded-For. See docs/configuration.md.""", address.getHostAddress());
        }
    }

    private static boolean isLocalNetwork(InetAddress address) {
        // isSiteLocalAddress doesn't cover IPv6 unique local addresses, which are fc00::/7.
        return address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || (address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc);
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
