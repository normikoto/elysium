package dev.biribiri.elysium.activitypub.media;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class MediaRejectionTask {

    private final MediaRejections rejections;

    /**
     * @param rejections lazy, because it needs the scheduler, which needs this task
     */
    MediaRejectionTask(@Lazy MediaRejections rejections) {
        this.rejections = rejections;
    }

    @Bean
    Task<MediaRejectionJob> mediaRejection() {
        return Jobs.task(MediaRejections.TASK, rejections::run);
    }
}
