package dev.biribiri.elysium.ratelimit;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Limits how many things can run at once for each key. A key is only tracked while something is running for it.
 */
public final class RunningLimit {

    private final int limit;
    private final ConcurrentHashMap<String, Integer> running = new ConcurrentHashMap<>();

    /**
     * One condition for every key, since waits are short and few. Counting the waiters lets {@link #finish} skip the lock
     * when nobody's waiting, which is nearly always.
     */
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition finished = lock.newCondition();
    private final AtomicInteger waiting = new AtomicInteger();

    public RunningLimit(int limit) {
        this.limit = limit;
    }

    /**
     * @return whether there was room. If so, this needs a call to {@link #finish} afterward.
     */
    public boolean tryStart(String key) {
        return tryStart(key, limit);
    }

    /**
     * Starts something that has a lower limit than others counted under the same key, so that it leaves them some room.
     *
     * @param limit how many can be running, of any kind, for this to start
     * @return whether there was room. If so, this needs a call to {@link #finish} afterward.
     */
    public boolean tryStart(String key, int limit) {
        var started = new boolean[1];
        running.compute(key, (ignored, current) -> {
            var count = current == null ? 0 : current;
            started[0] = count < limit;
            return started[0] ? Integer.valueOf(count + 1) : current;
        });
        return started[0];
    }

    /**
     * Like {@link #tryStart(String, int)}, but waits for room for up to this long. An interrupted wait gives up, and keeps
     * the thread's interrupt.
     *
     * @return whether there was room in time. If so, this needs a call to {@link #finish} afterward.
     */
    public boolean tryStart(String key, int limit, Duration wait) {
        var deadline = System.nanoTime() + wait.toNanos();
        lock.lock();
        // Counted before trying, so that a finish after a failed try is sure to see this waiter, and signal it.
        waiting.incrementAndGet();
        try {
            while (!tryStart(key, limit)) {
                var left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    finished.awaitNanos(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            waiting.decrementAndGet();
            lock.unlock();
        }
    }

    public void finish(String key) {
        running.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
        if (waiting.get() > 0) {
            lock.lock();
            try {
                finished.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    int trackedKeys() {
        return running.size();
    }
}
