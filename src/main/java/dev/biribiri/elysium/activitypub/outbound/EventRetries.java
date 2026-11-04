package dev.biribiri.elysium.activitypub.outbound;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Tries again for events whose listener failed, or whose node stopped before it finished, since otherwise those statuses
 * would never be sent. This is a recurring job, rather than Spring Modulith's option to do this at startup, because that
 * would also run in commands, and on every node that starts. This only runs on one node at a time, and never in a command.
 *
 * <p>A listener can end up running twice for an event that's only slow, so listeners have to be safe to repeat.
 */
@Configuration(proxyBeanMethods = false)
class EventRetries {

    private static final Duration EVERY = Duration.ofMinutes(5);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    /**
     * Far longer than a listener takes, so one that's still marked as being retried after this was on a node that stopped.
     */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(15);

    private final IncompleteEventPublications publications;
    private final JdbcClient jdbc;

    EventRetries(IncompleteEventPublications publications, JdbcClient jdbc) {
        this.publications = publications;
        this.jdbc = jdbc;
    }

    @Bean
    Task<Void> eventRetry() {
        return Tasks.recurring("event-retry", FixedDelay.of(EVERY)).execute((instance, context) -> retry());
    }

    void retry() {
        // Retrying skips what's marked as being retried, so these would otherwise stay stuck.
        jdbc.sql("UPDATE event_publication SET status = 'FAILED' WHERE status = 'RESUBMITTED' AND last_resubmission_date < :before")
                .param("before", Instant.now().minus(ABANDONED_AFTER).atOffset(ZoneOffset.UTC))
                .update();
        publications.resubmitIncompletePublicationsOlderThan(RETRY_AFTER);
    }
}
