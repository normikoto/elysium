package dev.biribiri.elysium.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how many things can run at once for each key. A key is only tracked while something is running for it.
 */
public final class RunningLimit {

    private final int limit;
    private final ConcurrentHashMap<String, Integer> running = new ConcurrentHashMap<>();

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

    public void finish(String key) {
        running.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
    }

    int trackedKeys() {
        return running.size();
    }
}
