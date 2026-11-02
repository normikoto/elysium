package dev.biribiri.elysium.auth.internal;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Limits how many passwords are hashed at once. Each hash takes a lot of memory and anyone can trigger one by trying to
 * log in. Virtual threads don't cap concurrent requests the way a worker pool does, so without this enough requests
 * would exhaust the heap.
 */
class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final Semaphore permits;
    private final Duration maxWait;

    ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration maxWait) {
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    @Override
    public String encode(CharSequence password) {
        return limited(() -> delegate.encode(password));
    }

    @Override
    public boolean matches(CharSequence password, String encoded) {
        return limited(() -> delegate.matches(password, encoded));
    }

    @Override
    public boolean upgradeEncoding(String encoded) {
        return delegate.upgradeEncoding(encoded);
    }

    private <T> T limited(Supplier<T> hashing) {
        try {
            if (!permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new PasswordHashingBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PasswordHashingBusyException();
        }
        try {
            return hashing.get();
        } finally {
            permits.release();
        }
    }
}
