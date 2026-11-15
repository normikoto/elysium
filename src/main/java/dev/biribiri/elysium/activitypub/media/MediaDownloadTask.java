package dev.biribiri.elysium.activitypub.media;

import com.github.kagkarlsson.scheduler.task.Task;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class MediaDownloadTask {

    private final MediaDownloads downloads;

    /**
     * @param downloads lazy, because it needs the scheduler, which needs this task
     */
    MediaDownloadTask(@Lazy MediaDownloads downloads) {
        this.downloads = downloads;
    }

    @Bean
    Task<MediaDownloadJob> mediaDownload() {
        return Jobs.task(MediaDownloads.TASK, downloads::run);
    }

    @Bean
    Task<ProfileImageDownloadJob> profileImageDownload() {
        return Jobs.task(MediaDownloads.PROFILE_IMAGE_TASK, downloads::run);
    }

    @Bean
    Task<EmojiDownloadJob> emojiDownload() {
        return Jobs.task(MediaDownloads.EMOJI_TASK, downloads::run);
    }
}
