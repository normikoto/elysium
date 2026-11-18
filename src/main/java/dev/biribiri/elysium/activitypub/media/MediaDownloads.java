package dev.biribiri.elysium.activitypub.media;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import dev.biribiri.elysium.actor.ProfileImage;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteProfileImageAdded;
import dev.biribiri.elysium.content.AttachmentProperties;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.InvalidMediaAttachmentException;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.ReceivedMedia;
import dev.biribiri.elysium.content.RemoteEmojiAdded;
import dev.biribiri.elysium.content.RemoteMediaAdded;
import dev.biribiri.elysium.moderation.DomainBlocks;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Downloads other servers' attachments, emoji, avatars and headers, so that they're served from here, and people's browsers don't fetch them from
 * those servers. One that can't be downloaded stays a link to its server's copy.
 *
 * <p>Downloads are large and slow compared with documents, so only a few run at once on each node, and only one to each
 * host, which also takes one of {@link HostRequests}' fetch slots while it's fetching. A server's media is often on the
 * server's own host, so that leaves the rest of its slots for key fetches and deliveries. One that can't start waits a
 * little, without counting as a failure. Each server can only have so much downloaded in an hour, counted
 * by its author's server rather than the file's host, which is often somewhere else. Past that, they're linked until
 * the {@link MediaProxyController proxy} is asked for them once there's room again. Like the inbox's limits, this is
 * counted in memory, on each node.
 *
 * <p>The proxy downloads an attachment, avatar, or header when a browser asks for it, which is how one comes back after
 * its copy has been removed to save space. Anyone can ask for these URLs, so they have slots of their own, and never take
 * the jobs', and a request never waits for room: one that would gets a link instead. A request waits only {@link
 * #ON_DEMAND_WAIT} for the download, which carries on without it, so that a slow server leaves browsers linked rather
 * than hanging, and the next view gets the copy. Requests for the same file share one download, and a file whose server
 * failed isn't asked for again for {@link #RETRY_FAILED_AFTER}.
 */
@Service
public class MediaDownloads {

    static final TaskDescriptor<MediaDownloadJob> TASK = TaskDescriptor.of("media-download", MediaDownloadJob.class);
    static final TaskDescriptor<EmojiDownloadJob> EMOJI_TASK = TaskDescriptor.of("emoji-download", EmojiDownloadJob.class);
    static final TaskDescriptor<ProfileImageDownloadJob> PROFILE_IMAGE_TASK =
            TaskDescriptor.of("profile-image-download", ProfileImageDownloadJob.class);

    private static final Logger log = LoggerFactory.getLogger(MediaDownloads.class);

    private static final int MAX_RUNNING = 8;
    private static final Duration WAIT_FOR_ROOM = Duration.ofSeconds(5);
    private static final Duration DEADLINE = Duration.ofMinutes(2);
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_TRACKED_SERVERS = 100_000;
    private static final int MAX_ON_DEMAND = 4;
    static final Duration ON_DEMAND_WAIT = Duration.ofSeconds(5);
    private static final Duration RETRY_FAILED_AFTER = Duration.ofMinutes(1);
    private static final int MAX_FAILURES_TRACKED = 10_000;

    private final SchedulerClient scheduler;
    private final MediaAttachments attachments;
    private final ActorService actors;
    private final FederationHttpClient http;
    private final HostRequests hostRequests;
    private final AttachmentProperties properties;
    private final RateLimiter budget;
    private final MediaRejections rejections;
    private final CustomEmojis emojis;
    private final DomainBlocks blocks;
    private final ProfileService profiles;
    private final Semaphore running = new Semaphore(MAX_RUNNING);
    private final Set<String> hostsDownloading = ConcurrentHashMap.newKeySet();
    private final Semaphore runningOnDemand = new Semaphore(MAX_ON_DEMAND);
    private final ConcurrentHashMap<String, CompletableFuture<Void>> onDemand = new ConcurrentHashMap<>();
    private final Cache<String, Boolean> recentlyFailed = Caffeine.newBuilder()
            .expireAfterWrite(RETRY_FAILED_AFTER)
            .maximumSize(MAX_FAILURES_TRACKED)
            .build();

    /**
     * @param scheduler lazy like everywhere else it's used, so that this doesn't affect when the scheduler is created
     */
    MediaDownloads(@Lazy SchedulerClient scheduler, MediaAttachments attachments, ActorService actors,
                   FederationHttpClient http, HostRequests hostRequests, AttachmentProperties properties,
                   MediaRejections rejections, CustomEmojis emojis, DomainBlocks blocks, ProfileService profiles) {
        this.profiles = profiles;
        this.rejections = rejections;
        this.emojis = emojis;
        this.blocks = blocks;
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

    /**
     * An emoji is queued each time a status uses it until it's downloaded, which doesn't queue a second job while one is
     * waiting.
     */
    @ApplicationModuleListener
    public void on(RemoteEmojiAdded event) {
        if (!properties.remoteCacheEnabled()) {
            return;
        }
        var now = Instant.now();
        for (var id : event.emojiIds()) {
            Jobs.schedule(scheduler, EMOJI_TASK, new EmojiDownloadJob(id, now), id.toString());
        }
    }

    /**
     * An image is queued each time its profile is refreshed until it's downloaded, which doesn't queue a second job while
     * one is waiting. The job's ID includes the image's URL, so that a job parked for an image the profile no longer has
     * doesn't keep its replacement from being queued.
     */
    @ApplicationModuleListener
    public void on(RemoteProfileImageAdded event) {
        if (!properties.remoteCacheEnabled()) {
            return;
        }
        if (event.avatar()) {
            scheduleProfileImage(event.actorId(), false);
        }
        if (event.header()) {
            scheduleProfileImage(event.actorId(), true);
        }
    }

    private void scheduleProfileImage(UUID actorId, boolean header) {
        profiles.findImageToDownload(actorId, header).ifPresent(url -> Jobs.schedule(scheduler, PROFILE_IMAGE_TASK,
                new ProfileImageDownloadJob(actorId, header, Instant.now()),
                actorId.toString(), header ? "header" : "avatar", url.toString()));
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
        // A filter added since the attachment was stored. Its job marks the attachment as rejected.
        if (rejections.isRejected(author.get().id(), author.get().uri())) {
            return Jobs.done();
        }
        var server = ServerActivityLimits.serverKey(author.get().uri());
        return withRoom(url, host -> download(url, host, server, attachmentTarget(job.attachmentId())));
    }

    /**
     * An emoji's image is small, but it's fetched as an attachment is, sharing the same slots and each server's budget.
     */
    CompletionHandler<EmojiDownloadJob> run(EmojiDownloadJob job) {
        if (!properties.remoteCacheEnabled()) {
            return Jobs.done();
        }
        var source = emojis.findForDownload(job.emojiId());
        if (source.isEmpty()) {
            return Jobs.done();
        }
        var url = source.get().url();
        var server = URI.create("https://" + source.get().domain() + "/");
        if (url.getHost() == null || server.getHost() == null) {
            return Jobs.done();
        }
        // A filter added since the emoji was stored. Its job removes copies already kept.
        if (blocks.rejectsMedia(server.getHost())) {
            emojis.giveUpCaching(job.emojiId());
            return Jobs.done();
        }
        return withRoom(url, host -> downloadEmoji(job, url, host, ServerActivityLimits.serverKey(server)));
    }

    /**
     * Avatars and headers are fetched as attachments are, sharing the same slots and each server's budget.
     */
    CompletionHandler<ProfileImageDownloadJob> run(ProfileImageDownloadJob job) {
        if (!properties.remoteCacheEnabled()) {
            return Jobs.done();
        }
        var url = profiles.findImageToDownload(job.actorId(), job.header());
        var actor = actors.findById(job.actorId()).filter(Actor::isActive);
        if (url.isEmpty() || actor.isEmpty() || url.get().getHost() == null) {
            return Jobs.done();
        }
        // A filter added since the profile was stored. Its job removes copies already kept.
        if (rejections.isRejected(actor.get().id(), actor.get().uri())) {
            profiles.giveUpCaching(job.actorId(), job.header(), url.get());
            return Jobs.done();
        }
        var server = ServerActivityLimits.serverKey(actor.get().uri());
        return withRoom(url.get(), host -> download(url.get(), host, server,
                profileImageTarget(job.actorId(), job.header(), url.get())));
    }

    /**
     * Downloads another server's attachment for {@link MediaProxyController}, if it's one that's served through it, and
     * then finds it again.
     *
     * @return empty if it isn't served at all, because it's gone or its author isn't active
     */
    public Optional<MediaAttachment> downloadAttachmentNow(UUID id) {
        var found = attachments.findRemote(id);
        if (found.isEmpty()) {
            return found;
        }
        var author = actors.findById(found.get().accountId()).filter(Actor::isActive);
        if (author.isEmpty()) {
            return Optional.empty();
        }
        var url = found.get().remoteUrl();
        if (!found.get().isProxied() || !properties.remoteCacheEnabled() || url.getHost() == null
                || rejections.isRejected(author.get().id(), author.get().uri())) {
            return found;
        }
        var server = ServerActivityLimits.serverKey(author.get().uri());
        onDemand("attachment " + id, url, server, attachmentTarget(id),
                () -> attachments.findForDownload(id).isPresent());
        return attachments.findRemote(id);
    }

    /**
     * Like {@link #downloadAttachmentNow}, for an account on another server's avatar or header.
     *
     * @return empty if the account isn't active, or has no such image. A local account's is its own copy
     */
    public Optional<ProfileImage> downloadProfileImageNow(UUID actorId, boolean header) {
        var actor = actors.findById(actorId).filter(Actor::isActive);
        if (actor.isEmpty()) {
            return Optional.empty();
        }
        var image = profiles.findImage(actorId, header);
        if (image.isEmpty() || !image.get().isProxied() || !properties.remoteCacheEnabled()) {
            return image;
        }
        var url = profiles.findImageToDownload(actorId, header);
        if (url.isEmpty() || url.get().getHost() == null || rejections.isRejected(actor.get().id(), actor.get().uri())) {
            return image;
        }
        var server = ServerActivityLimits.serverKey(actor.get().uri());
        onDemand("profile " + actorId + (header ? " header " : " avatar ") + url.get(), url.get(), server,
                profileImageTarget(actorId, header, url.get()),
                () -> profiles.findImageToDownload(actorId, header).filter(url.get()::equals).isPresent());
        return profiles.findImage(actorId, header);
    }

    /**
     * Starts a download for a request, or joins the one already running for the same file, and waits up to {@link
     * #ON_DEMAND_WAIT} for it.
     *
     * @param needed whether it still has to be downloaded, checked once the download has its host, since a job or another
     *               node can have kept it meanwhile
     */
    private void onDemand(String key, URI url, String server, Target<?> target, BooleanSupplier needed) {
        if (recentlyFailed.getIfPresent(key) != null) {
            return;
        }
        var mine = new CompletableFuture<Void>();
        var download = onDemand.putIfAbsent(key, mine);
        if (download == null) {
            download = mine;
            if (!start(key, url, server, target, needed, mine)) {
                return;
            }
        }
        try {
            download.get(ON_DEMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            // Linked for this request, and the download carries on for the next.
        }
    }

    /**
     * @return whether it started, which it doesn't if there's no room, so that a request never waits for it
     */
    private boolean start(String key, URI url, String server, Target<?> target, BooleanSupplier needed,
                          CompletableFuture<Void> done) {
        var host = url.getHost().toLowerCase(Locale.ROOT);
        if (!runningOnDemand.tryAcquire()) {
            finish(key, done);
            return false;
        }
        if (!hostsDownloading.add(host)) {
            runningOnDemand.release();
            finish(key, done);
            return false;
        }
        try {
            Thread.ofVirtual().name("media-proxy").start(() -> {
                try {
                    if (needed.getAsBoolean()) {
                        attempt(url, host, server, target);
                    }
                } catch (RuntimeException e) {
                    log.debug("Couldn't download {} for a request, so it's linked for now: {}", url, e.getMessage());
                    recentlyFailed.put(key, true);
                } finally {
                    hostsDownloading.remove(host);
                    runningOnDemand.release();
                    finish(key, done);
                }
            });
        } catch (RuntimeException e) {
            hostsDownloading.remove(host);
            runningOnDemand.release();
            finish(key, done);
            throw e;
        }
        return true;
    }

    private void finish(String key, CompletableFuture<Void> done) {
        onDemand.remove(key, done);
        done.complete(null);
    }

    private <J> CompletionHandler<J> withRoom(URI url, Function<String, CompletionHandler<J>> download) {
        if (!running.tryAcquire()) {
            return Jobs.waitUntil(Instant.now().plus(WAIT_FOR_ROOM));
        }
        var host = url.getHost().toLowerCase(Locale.ROOT);
        if (!hostsDownloading.add(host)) {
            running.release();
            return Jobs.waitUntil(Instant.now().plus(WAIT_FOR_ROOM));
        }
        try {
            return download.apply(host);
        } finally {
            hostsDownloading.remove(host);
            running.release();
        }
    }

    /**
     * What's done with a download, which is where attachments and emoji differ.
     *
     * @param <T> what the body is read into
     */
    private interface Target<T> {

        long maxBytes();

        FederationHttpClient.BodyReader<T> reader();

        long size(T body);

        /**
         * It can't or mustn't be downloaded, so it stays a link, and isn't tried again.
         */
        void giveUp();

        /**
         * Its server has had as much downloaded as it can for now.
         */
        void overBudget();

        void keep(T body);

        /**
         * Called for every body read, whether or not it's kept.
         */
        void release(T body);
    }

    private Target<ReceivedMedia> attachmentTarget(UUID id) {
        return new Target<>() {
            public long maxBytes() {
                return properties.largest().toBytes();
            }

            public FederationHttpClient.BodyReader<ReceivedMedia> reader() {
                return (contentType, body) -> attachments.receive(body);
            }

            public long size(ReceivedMedia body) {
                return body.size();
            }

            public void giveUp() {
                attachments.giveUpCaching(id);
            }

            // Left to the proxy, which downloads it when it's next viewed, rather than linked for good.
            public void overBudget() {
            }

            public void keep(ReceivedMedia body) {
                attachments.cacheRemote(id, body);
            }

            public void release(ReceivedMedia body) {
                body.close();
            }
        };
    }

    private CompletionHandler<EmojiDownloadJob> downloadEmoji(EmojiDownloadJob job, URI url, String host, String server) {
        var id = job.emojiId();
        return download(url, host, server, new Target<byte[]>() {
            public long maxBytes() {
                return emojis.maxImageSize().toBytes();
            }

            public FederationHttpClient.BodyReader<byte[]> reader() {
                return (contentType, body) -> body.readAllBytes();
            }

            public long size(byte[] body) {
                return body.length;
            }

            public void giveUp() {
                emojis.giveUpCaching(id);
            }

            // An emoji is shared by many statuses, so it's tried again the next time one uses it, rather than linked for
            // good.
            public void overBudget() {
            }

            public void keep(byte[] body) {
                emojis.cacheRemote(id, url, body);
            }

            public void release(byte[] body) {
            }
        });
    }

    private Target<byte[]> profileImageTarget(UUID actorId, boolean header, URI url) {
        return new Target<>() {
            public long maxBytes() {
                return profiles.maxImageSize().toBytes();
            }

            public FederationHttpClient.BodyReader<byte[]> reader() {
                return (contentType, body) -> body.readAllBytes();
            }

            public long size(byte[] body) {
                return body.length;
            }

            public void giveUp() {
                profiles.giveUpCaching(actorId, header, url);
            }

            // Tried again the next time the profile is refreshed or viewed, rather than linked for good.
            public void overBudget() {
            }

            public void keep(byte[] body) {
                profiles.cacheRemoteImage(actorId, header, url, body);
            }

            public void release(byte[] body) {
            }
        };
    }

    private enum Attempt {
        /** It's been downloaded, or it won't be. */
        FINISHED,
        /** Its host had no fetch slot free. */
        NO_ROOM
    }

    private <J, T> CompletionHandler<J> download(URI url, String host, String server, Target<T> target) {
        return attempt(url, host, server, target) == Attempt.FINISHED
                ? Jobs.done()
                : Jobs.waitUntil(Instant.now().plus(WAIT_FOR_ROOM));
    }

    /**
     * @throws RuntimeException if it failed in a way that's worth trying again
     */
    private <T> Attempt attempt(URI url, String host, String server, Target<T> target) {
        // Checked before downloading too, so that a server past its budget isn't fetched from at all.
        var probe = budget.tryAcquire(server);
        if (probe.isEmpty()) {
            log.debug("Not downloading {}, since {} has had as much downloaded as it can for now", url, server);
            target.overBudget();
            return Attempt.FINISHED;
        }
        probe.get().release();
        if (!hostRequests.tryStartFetch(host)) {
            return Attempt.NO_ROOM;
        }
        FederationHttpClient.Download<T> downloaded;
        try {
            downloaded = http.download(url, target.maxBytes(), DEADLINE, MAX_REDIRECTS, target.reader());
        } catch (BlockedTargetException | ResponseTooLargeException | InvalidMediaAttachmentException e) {
            log.debug("Not downloading {}: {}", url, e.getMessage());
            target.giveUp();
            return Attempt.FINISHED;
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
            target.giveUp();
            return Attempt.FINISHED;
        }
        var body = downloaded.body();
        try {
            if (budget.tryAcquire(server, Math.max(1, target.size(body))).isEmpty()) {
                log.debug("Not keeping {}, since {} has had as much downloaded as it can for now", url, server);
                target.overBudget();
                return Attempt.FINISHED;
            }
            target.keep(body);
        } finally {
            target.release(body);
        }
        return Attempt.FINISHED;
    }
}
