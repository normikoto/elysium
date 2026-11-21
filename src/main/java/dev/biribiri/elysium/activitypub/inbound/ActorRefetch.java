package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * Fetches an actor's document a second time after its {@code Update}. Mastodon serves each actor document from a cache
 * for 3 minutes, which an {@code Update} doesn't clear, so the fetch made as the {@code Update} arrives usually gets the
 * document from before the change, and the copy here would otherwise keep that until it's next refreshed, a day later.
 */
@Component
class ActorRefetch {

    static final TaskDescriptor<ActorRefetchJob> TASK = TaskDescriptor.of("actor-refetch", ActorRefetchJob.class);

    /**
     * Longer than Mastodon's cache, counted from the {@code Update}, which it sends once the change is saved.
     */
    static final Duration DELAY = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ActorRefetch.class);

    private final RemoteActors remoteActors;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs this job's task, which needs this
     */
    ActorRefetch(RemoteActors remoteActors, @Lazy SchedulerClient scheduler) {
        this.remoteActors = remoteActors;
        this.scheduler = scheduler;
    }

    /**
     * One job for each {@code Update}, rather than each actor, so that a later one isn't covered by a job that runs too soon
     * after it.
     */
    void queue(URI actor, String activityId) {
        var now = Instant.now();
        Jobs.scheduleAt(scheduler, TASK, new ActorRefetchJob(actor.toString(), now), now.plus(DELAY),
                actor.toString(), activityId);
    }

    @Bean
    static Task<ActorRefetchJob> actorDocumentRefetch(ObjectProvider<ActorRefetch> actorRefetch) {
        return Jobs.task(TASK, job -> actorRefetch.getObject().run(job));
    }

    /**
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<ActorRefetchJob> run(ActorRefetchJob job) {
        try {
            remoteActors.refresh(URI.create(job.actorUri()));
        } catch (PermanentJobFailure e) {
            log.debug("Not fetching {} again: {}", job.actorUri(), e.getMessage());
        }
        return Jobs.done();
    }
}
