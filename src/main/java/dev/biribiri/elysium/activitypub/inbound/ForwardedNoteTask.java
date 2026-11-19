package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class ForwardedNoteTask {

    private final ForwardedNotes notes;

    /**
     * @param notes lazy, because it needs the scheduler, which needs this task
     */
    ForwardedNoteTask(@Lazy ForwardedNotes notes) {
        this.notes = notes;
    }

    @Bean
    Task<ForwardedNoteJob> forwardedNote() {
        return Jobs.task(ForwardedNotes.TASK, notes::run);
    }
}
