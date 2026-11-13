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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Limits login and sign-up attempts, which are expensive because each one hashes a password, and app registrations.
 *
 * <p>An attempt takes its slots before hashing and gets them back afterwards if it shouldn't count. Counting failures
 * only after hashing would let a burst of concurrent requests all pass the check before any failure was recorded. The
 * slots are kept on the request until the attempt finishes, and only the first way of finishing it has any effect.
 *
 * <p>The limit on one username from one address is the strict one. The limit on a username from anywhere is much looser,
 * so that it takes a lot of effort for someone else to lock an account out.
 */
@Component
public class AuthRateLimits {

    private static final int MAX_TRACKED_KEYS = 100_000;
    private static final String USERNAME_PARAMETER = "username";
    private static final String LOGIN_ATTEMPT_ATTRIBUTE = AuthRateLimits.class.getName() + ".loginAttempt";
    private static final String SIGN_UP_SLOT_ATTRIBUTE = AuthRateLimits.class.getName() + ".signUpSlot";
    private static final String APP_REGISTRATION_SLOT_ATTRIBUTE =
            AuthRateLimits.class.getName() + ".appRegistrationSlot";

    private final boolean enabled;
    private final ClientAddresses clients;
    private final RateLimiter loginsByAddress;
    private final RateLimiter loginsByUsernameAndAddress;
    private final RateLimiter loginsByUsername;
    private final RateLimiter signUpsByAddress;
    private final RateLimiter appRegistrationsByAddress;

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
        // Some clients register a new app every time someone logs in, and several of them can share one address.
        this.appRegistrationsByAddress = new RateLimiter(30, Duration.ofHours(1), MAX_TRACKED_KEYS, ticker);
    }

    /**
     * Starts a login attempt. If it's allowed, it counts as a failure unless it finishes with {@link #loginSucceeded} or
     * {@link #loginNotCounted}.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginLogin(HttpServletRequest request) {
        return begin(request, loginUsername(request));
    }

    /**
     * Starts checking the password of an account that someone's already logged in to, which counts in the same way as trying to
     * log in to it, so that a stolen session doesn't get around the limits on guessing its password. This finishes in the same
     * ways as a login attempt.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginPasswordCheck(HttpServletRequest request, String username) {
        return begin(request, Optional.of(username.toLowerCase(Locale.ROOT)));
    }

    /**
     * Starts checking a two-factor code for an account whose password was right, or that someone's logged in to. Codes count
     * against the same limits as passwords, but separately from the account's passwords, so that a mistyped code doesn't use
     * up password attempts. This finishes in the same ways as a login attempt.
     *
     * @return how long to wait, if the attempt isn't allowed
     */
    Optional<Duration> beginCodeCheck(HttpServletRequest request, UUID actorId) {
        // Usernames can't contain a colon, so this can't be anyone's username.
        return begin(request, Optional.of("code:" + actorId));
    }

    private Optional<Duration> begin(HttpServletRequest request, Optional<String> username) {
        if (!enabled) {
            return Optional.empty();
        }
        var address = clients.keyFor(request);
        // This comes first so that one address can't push other counts out of the caches with made-up usernames.
        var addressSlot = loginsByAddress.tryAcquire(address);
        if (addressSlot.isEmpty()) {
            return Optional.of(loginsByAddress.timeUntilReset(address));
        }
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
        return begin(request, signUpsByAddress, SIGN_UP_SLOT_ATTRIBUTE);
    }

    /**
     * Gives back the slot of a sign-up that failed because of the server, such as when it's busy.
     */
    void signUpNotCounted(HttpServletRequest request) {
        release(request, SIGN_UP_SLOT_ATTRIBUTE);
    }

    /**
     * Starts registering an app, which needs no credentials and stores a client that's kept, so anyone could otherwise
     * keep growing the database.
     *
     * @return how long to wait, if the registration isn't allowed
     */
    public Optional<Duration> beginAppRegistration(HttpServletRequest request) {
        return begin(request, appRegistrationsByAddress, APP_REGISTRATION_SLOT_ATTRIBUTE);
    }

    /**
     * Gives back the slot of an app registration that failed because of the server.
     */
    public void appRegistrationNotCounted(HttpServletRequest request) {
        release(request, APP_REGISTRATION_SLOT_ATTRIBUTE);
    }

    private Optional<Duration> begin(HttpServletRequest request, RateLimiter limiter, String slotAttribute) {
        if (!enabled) {
            return Optional.empty();
        }
        var address = clients.keyFor(request);
        var slot = limiter.tryAcquire(address);
        if (slot.isEmpty()) {
            return Optional.of(limiter.timeUntilReset(address));
        }
        request.setAttribute(slotAttribute, slot.get());
        return Optional.empty();
    }

    private static void release(HttpServletRequest request, String slotAttribute) {
        if (request.getAttribute(slotAttribute) instanceof Slot slot) {
            request.removeAttribute(slotAttribute);
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
