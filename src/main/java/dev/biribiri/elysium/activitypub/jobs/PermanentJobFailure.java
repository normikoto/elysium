package dev.biribiri.elysium.activitypub.jobs;

/**
 * A job can never succeed, so it shouldn't be tried again. Anything else a job throws counts as temporary. The message is
 * shown to admins, so it mustn't include the contents of an activity.
 */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }

    public PermanentJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
