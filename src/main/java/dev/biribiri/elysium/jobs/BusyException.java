package dev.biribiri.elysium.jobs;

/**
 * Something a job needs had no room for it. That says nothing about whether it works, so the job waits a moment and runs
 * again, as {@link Jobs#task} describes, rather than failing and backing off.
 *
 * <p>It's unchecked so that it can pass through the callers between where it's thrown and the job, which turn other
 * failures into their own answers.
 */
public class BusyException extends RuntimeException {

    public BusyException(String message) {
        super(message);
    }
}
