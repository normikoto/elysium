package dev.biribiri.elysium.auth.internal;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import dev.biribiri.elysium.ratelimit.RateLimiter.Slot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Limits login and sign-up attempts, which are expensive because each one hashes a password.
 *
 * <p>An attempt takes its slots before hashing and gets them back afterwards if it shouldn't count. Counting failures
 * only after hashing would let a burst of concurrent requests all pass the check before any failure was recorded. The
 * slots are kept on the request until the attempt finishes, and only the first way of finishing it has any effect.
 *
 * <p>The limit on one username from one address is the strict one. The limit on a username from anywhere is much looser,
 * so that it takes a lot of effort for someone else to lock an account out.
 */
@Component
class AuthRateLimits {

    private static final int MAX_TRACKED_KEYS = 100_000;
    private static final String USERNAME_PARAMETER = "username";
    private static final String LOGIN_ATTEMPT_ATTRIBUTE = AuthRateLimits.class.getName() + ".loginAttempt";
    private static final String SIGN_UP_SLOT_ATTRIBUTE = AuthRateLimits.class.getName() + ".signUpSlot";

    private final boolean enabled;
    private final ClientAddresses clients;
    private final RateLimiter loginsByAddress;
    private final RateLimiter loginsByUsernameAndAddress;
    private final RateLimiter loginsByUsername;
    private final RateLimiter signUpsByAddress;

    @Autowired
    AuthRateLimits(RateLimitProperties properties, ClientAddresses clients) {
        this(properties, clients, Ticker.systemTicker());
    }

    AuthRateLimits(RateLimitProperties properties, ClientAddresses clients, Ticker ticker) {
        this.enabled = properties.enabled();
        this.clients = clients;
        this.loginsByAddress = new RateLimiter(20, Duration.ofMinutes(15), MAX_TRACKED_KEYS, ticker);
        this.loginsByUsernameAndAddress = new RateLimiter(5, Duration.ofMinutes(15), MAX_TRACKED_KEYS, ticker);
        this.loginsByUsername = new RateLimiter(50, Duration.ofHours(1), MAX_TRACKED_KEYS, ticker);
        this.signUpsByAddress = new RateLimiter(5, Duration.ofHours(1), MAX_TRACKED_KEYS, ticker);
    }

    /**
     * Starts a login attempt. If it's allowed, it counts as a failure unless it finishes with {@link #loginSucceeded} or
     * {@link #loginNotCounted}.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginLogin(HttpServletRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        var address = clients.keyFor(request);
        // This comes first so that one address can't push other counts out of the caches with made-up usernames.
        var addressSlot = loginsByAddress.tryAcquire(address);
        if (addressSlot.isEmpty()) {
            return Optional.of(loginsByAddress.timeUntilReset(address));
        }
        var username = loginUsername(request);
        if (username.isEmpty()) {
            request.setAttribute(LOGIN_ATTEMPT_ATTRIBUTE, new LoginAttempt(addressSlot.get(), null, null));
            return Optional.empty();
        }

        // Usernames can't contain this separator.
        var pair = username.get() + "|" + address;
        var pairSlot = loginsByUsernameAndAddress.tryAcquire(pair);
        if (pairSlot.isEmpty()) {
            addressSlot.get().release();
            return Optional.of(loginsByUsernameAndAddress.timeUntilReset(pair));
        }
        var usernameSlot = loginsByUsername.tryAcquire(username.get());
        if (usernameSlot.isEmpty()) {
            pairSlot.get().release();
            addressSlot.get().release();
            return Optional.of(loginsByUsername.timeUntilReset(username.get()));
        }
        request.setAttribute(
                LOGIN_ATTEMPT_ATTRIBUTE, new LoginAttempt(addressSlot.get(), pairSlot.get(), usernameSlot.get()));
        return Optional.empty();
    }

    /**
     * Finishes a login attempt that succeeded. This doesn't erase the earlier failures from the address or against the
     * username, so logging in to your own account doesn't help with guessing someone else's password.
     */
    void loginSucceeded(HttpServletRequest request) {
        finishLogin(request, Slot::resetKey);
    }

    /**
     * Finishes a login attempt that failed because of its credentials, so it stays counted.
     */
    void loginFailed(HttpServletRequest request) {
        request.removeAttribute(LOGIN_ATTEMPT_ATTRIBUTE);
    }

    /**
     * Finishes a login attempt that failed for a reason other than its credentials, such as a busy server, by giving
     * back its slots.
     */
    void loginNotCounted(HttpServletRequest request) {
        finishLogin(request, Slot::release);
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
        var address = clients.keyFor(request);
        var slot = signUpsByAddress.tryAcquire(address);
        if (slot.isEmpty()) {
            return Optional.of(signUpsByAddress.timeUntilReset(address));
        }
        request.setAttribute(SIGN_UP_SLOT_ATTRIBUTE, slot.get());
        return Optional.empty();
    }

    /**
     * Gives back the slot of a sign-up that failed because of the server, such as when it's busy.
     */
    void signUpNotCounted(HttpServletRequest request) {
        if (request.getAttribute(SIGN_UP_SLOT_ATTRIBUTE) instanceof Slot slot) {
            request.removeAttribute(SIGN_UP_SLOT_ATTRIBUTE);
            slot.release();
        }
    }

    private static void finishLogin(HttpServletRequest request, Consumer<Slot> finishPair) {
        if (request.getAttribute(LOGIN_ATTEMPT_ATTRIBUTE) instanceof LoginAttempt attempt) {
            request.removeAttribute(LOGIN_ATTEMPT_ATTRIBUTE);
            attempt.address().release();
            if (attempt.username() != null) {
                finishPair.accept(attempt.usernameAndAddress());
                attempt.username().release();
            }
        }
    }

    /**
     * The account a login would try. This has to match what Spring Security looks up, or changing how a username is
     * written would get around its limits, so it's trimmed in the same way as {@code UsernamePasswordAuthenticationFilter}
     * and lowercased because accounts are looked up regardless of case.
     *
     * <p>Other usernames can't belong to an account, so they have nothing to protect and only the address's limit
     * applies. Leaving them out also keeps strings as long as the largest allowed request out of the caches.
     */
    private static Optional<String> loginUsername(HttpServletRequest request) {
        var username = request.getParameter(USERNAME_PARAMETER);
        if (username == null) {
            return Optional.empty();
        }
        var trimmed = username.trim();
        if (!Actor.isValidLocalUsername(trimmed)) {
            return Optional.empty();
        }
        return Optional.of(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * The slots of a login in progress. The last two are null if the username can't belong to an account.
     */
    private record LoginAttempt(Slot address, Slot usernameAndAddress, Slot username) {
    }
}
