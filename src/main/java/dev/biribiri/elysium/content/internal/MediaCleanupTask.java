package dev.biribiri.elysium.content.internal;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.media.MediaFiles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

/**
 * Removes attachments whose statuses are gone, uploads that weren't posted in time, copies of other servers' attachments
 * that have been kept long enough, and their files, as well as files that were never finished being received. Most statuses are removed in bulk, with nothing published for each, so this is what
 * finds their files. It's a recurring job, so only one node runs it at a time.
 */
@Configuration(proxyBeanMethods = false)
class MediaCleanupTask {

    static final String NAME = "media-cleanup";

    /**
     * Longer than any upload takes to receive, so that a file still arriving isn't removed.
     */
    private static final Duration TEMPORARY_FILES_KEPT = Duration.ofDays(1);

    @Bean
    Task<Void> mediaCleanup(MediaAttachments attachments, MediaFiles files) {
        return Tasks.recurring(NAME, FixedDelay.of(Duration.ofMinutes(15)))
                .execute((instance, context) -> {
                    attachments.removeOrphans();
                    attachments.pruneRemoteCopies();
                    files.removeStaleTemporaryFiles(Instant.now().minus(TEMPORARY_FILES_KEPT));
                });
    }
}
