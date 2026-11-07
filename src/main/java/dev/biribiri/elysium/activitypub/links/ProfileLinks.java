package dev.biribiri.elysium.activitypub.links;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.ProfileUpdated;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Queues checking a profile's links whenever it changes. Only fields that haven't been verified are checked, and a field
 * stays verified for as long as its value is the same, as on Mastodon.
 */
@Service
public class ProfileLinks {

    static final TaskDescriptor<ProfileLinksJob> TASK = TaskDescriptor.of("profile-links", ProfileLinksJob.class);

    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    ProfileLinks(@Lazy SchedulerClient scheduler) {
        this.scheduler = scheduler;
    }

    @ApplicationModuleListener
    public void on(ProfileUpdated event) {
        Jobs.schedule(scheduler, TASK, new ProfileLinksJob(event.actorId(), Instant.now()),
                event.actorId().toString(), event.at().toString());
    }
}
