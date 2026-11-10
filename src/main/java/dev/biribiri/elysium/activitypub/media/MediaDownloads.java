package dev.biribiri.elysium.activitypub.media;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import dev.biribiri.elysium.activitypub.http.ResponseTooLargeException;
import dev.biribiri.elysium.activitypub.inbound.ServerActivityLimits;
import dev.biribiri.elysium.activitypub.jobs.Jobs;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.InvalidMediaAttachmentException;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.ReceivedMedia;
import dev.biribiri.elysium.content.RemoteMediaAdded;
import dev.biribiri.elysium.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Downloads other servers' attachments, so that they're served from here, and people's browsers don't fetch them from
 * those servers. One that can't be downloaded stays a link to its server's copy.
 *
 * <p>Downloads are large and slow compared with documents, so only a few run at once on each node, and only one to each
 * host, which also takes one of {@link HostRequests}' fetch slots while it's fetching. A server's media is often on the
 * server's own host, so that leaves the rest of its slots for key fetches and deliveries. One that can't start waits a
 * little, without counting as a failure. Each server can only have so much downloaded in an hour, counted
 * by its author's server rather than the file's host, which is often somewhere else. Past that, attachments stay links.
 * Like the inbox's limits, this is counted in memory, on each node.
 */
@Service
public class MediaDownloads {

    static final TaskDescriptor<MediaDownloadJob> TASK = TaskDescriptor.of("media-download", MediaDownloadJob.class);

    private static final Logger log = LoggerFactory.getLogger(MediaDownloads.class);

    private static final int MAX_RUNNING = 8;
    private static final Duration WAIT_FOR_ROOM = Duration.ofSeconds(5);
    private static final Duration DEADLINE = Duration.ofMinutes(2);
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_TRACKED_SERVERS = 100_000;

    private final SchedulerClient scheduler;
    private final MediaAttachments attachments;
    private final ActorService actors;
    private final FederationHttpClient http;
    private final HostRequests hostRequests;
    private final AttachmentProperties properties;
    private final RateLimiter budget;
    private final Semaphore running = new Semaphore(MAX_RUNNING);
    private final Set<String> hostsDownloading = ConcurrentHashMap.newKeySet();

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    MediaDownloads(@Lazy SchedulerClient scheduler, MediaAttachments attachments, ActorService actors,
                   FederationHttpClient http, HostRequests hostRequests, AttachmentProperties properties) {
        this.scheduler = scheduler;
        this.attachments = attachments;
        this.actors = actors;
        this.http = http;
        this.hostRequests = hostRequests;
        this.properties = properties;
        this.budget = new RateLimiter(properties.remoteDownloadBudget().toBytes(), Duration.ofHours(1),
                MAX_TRACKED_SERVERS, Ticker.systemTicker());
    }

    /**
     * Repeats don't queue a second job while the first is waiting, and one that runs again finds nothing to do.
     */
    @ApplicationModuleListener
    public void on(RemoteMediaAdded event) {
        if (!properties.remoteCacheEnabled()) {
            return;
        }
        var now = Instant.now();
        for (var id : event.attachmentIds()) {
            Jobs.schedule(scheduler, TASK, new MediaDownloadJob(id, now), id.toString());
        }
    }

    CompletionHandler<MediaDownloadJob> run(MediaDownloadJob job) {
        if (!properties.remoteCacheEnabled()) {
            return Jobs.done();
        }
        var source = attachments.findForDownload(job.attachmentId());
        if (source.isEmpty()) {
            return Jobs.done();
        }
        var author = actors.findById(source.get().authorId()).filter(Actor::isActive);
        var url = source.get().url();
        if (author.isEmpty() || url.getHost() == null) {
            return Jobs.done();
        }
        if (!running.tryAcquire()) {
            return Jobs.rescheduleAt(Instant.now().plus(WAIT_FOR_ROOM));
        }
        var host = url.getHost().toLowerCase(Locale.ROOT);
        if (!hostsDownloading.add(host)) {
            running.release();
            return Jobs.rescheduleAt(Instant.now().plus(WAIT_FOR_ROOM));
        }
        try {
            return download(job, url, host, ServerActivityLimits.serverKey(author.get().uri()));
        } finally {
            hostsDownloading.remove(host);
            running.release();
        }
    }

    private CompletionHandler<MediaDownloadJob> download(MediaDownloadJob job, URI url, String host, String server) {
        var id = job.attachmentId();
        // Checked before downloading too, so that a server past its budget isn't fetched from at all.
        var probe = budget.tryAcquire(server);
        if (probe.isEmpty()) {
            log.debug("Not downloading {}, since {} has had as much downloaded as it can for now", url, server);
            attachments.giveUpCaching(id);
            return Jobs.done();
        }
        probe.get().release();
        if (!hostRequests.tryStartFetch(host)) {
            return Jobs.rescheduleAt(Instant.now().plus(WAIT_FOR_ROOM));
        }
        FederationHttpClient.Download<ReceivedMedia> downloaded;
        try {
            downloaded = http.download(url, properties.largest().toBytes(), DEADLINE, MAX_REDIRECTS,
                    (contentType, body) -> attachments.receive(body));
        } catch (BlockedTargetException | ResponseTooLargeException | InvalidMediaAttachmentException e) {
            log.debug("Not downloading {}: {}", url, e.getMessage());
            attachments.giveUpCaching(id);
            return Jobs.done();
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't download " + url, e);
        } finally {
            // Before the file is re-encoded, which can take a while and needs no connection.
            hostRequests.finishFetch(host);
        }
        var status = downloaded.status();
        if (status >= 500 || status == 408 || status == 429) {
            throw new IllegalStateException("Downloading " + url + " returned " + status);
        }
        if (downloaded.body() == null) {
            log.debug("Not downloading {}, which returned {}", url, status);
            attachments.giveUpCaching(id);
            return Jobs.done();
        }
        try (var file = downloaded.body()) {
            if (budget.tryAcquire(server, Math.max(1, file.size())).isEmpty()) {
                log.debug("Not keeping {}, since {} has had as much downloaded as it can for now", url, server);
                attachments.giveUpCaching(id);
                return Jobs.done();
            }
            attachments.cacheRemote(id, file);
        }
        return Jobs.done();
    }
}
