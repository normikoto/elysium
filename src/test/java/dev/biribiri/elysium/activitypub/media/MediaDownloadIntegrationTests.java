package dev.biribiri.elysium.activitypub.media;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestMedia;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.HostRequests;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.actor.ProfileImage;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.content.RemoteEdit;
import dev.biribiri.elysium.content.RemoteEmoji;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.media.MediaProperties;
import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Downloads other servers' attachments from a server on the loopback address. No job worker runs, so jobs are run
 * directly, apart from the tests that check they're queued.
 */
@Import({TestcontainersConfiguration.class, MediaDownloadIntegrationTests.Config.class})
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://downloads.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.attachments.max-image-size=100KB",
        "elysium.attachments.max-video-size=100KB",
        "elysium.attachments.max-audio-size=100KB",
        "elysium.attachments.remote-download-budget=50KB"
})
@AutoConfigureMockMvc
class MediaDownloadIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MediaDownloads downloads;
    @Autowired
    private MediaAttachments attachments;
    @Autowired
    private StatusService statuses;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private ActorService actors;
    @Autowired
    private MediaProperties media;
    @Autowired
    private InstanceProperties instance;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private HostRequests hostRequests;
    @Autowired
    private MediaRejections rejections;
    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private ProfileService profiles;

    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private HttpServer server;
    private Actor author;
    private String host;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        // Each test has its own server, and so its own download budget.
        host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        author = actors.upsertRemote(new RemoteActorProfile(uri, "s", host, URI.create(uri + "/inbox"), null, null, null,
                null));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void keepsACopyOfAnImageAndServesItFromHere() throws Exception {
        serve("/a.png", 200, "image/png", TestImages.png(800, 600));
        var attachment = attach("/a.png", "image/png");

        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));

        var cached = find(attachment);
        assertThat(cached.isCached()).isTrue();
        assertThat(cached.type()).isEqualTo(AttachmentType.IMAGE);
        assertThat(cached.contentType()).isEqualTo("image/webp");
        assertThat(cached.width()).isEqualTo(800);
        assertThat(cached.remoteUrl()).isEqualTo(attachment.remoteUrl());
        assertThat(cached.url(instance).toString()).startsWith("https://downloads.example/media/");
        assertThat(media.directory().resolve(cached.fileName())).exists();
        assertThat(media.directory().resolve(cached.previewName())).exists();

        // Running it again finds nothing to do.
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        assertThat(requests.get("/a.png")).hasValue(1);
    }

    @Test
    void keepsACopyOfAnEmojiAndReplacesItWhenItsImageChanges() throws Exception {
        serve("/blob.png", 200, "image/png", TestImages.png(64, 64));
        serve("/blob2.png", 200, "image/png", TestImages.png(32, 32));
        var noteUri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var statusId = remoteStatuses.storeRemote(new RemoteStatus(noteUri, null, author.id(), "<p>:blob:</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(), null, null, null,
                List.of(new RemoteEmoji("blob", null, fileUrl("/blob.png"), null)))).orElseThrow();
        var emoji = emojis.findForStatus(statusId).getFirst();
        assertThat(emoji.url(instance)).isEqualTo(fileUrl("/blob.png"));

        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));

        var cached = emojis.findForStatus(statusId).getFirst();
        assertThat(cached.url(instance).toString()).startsWith("https://downloads.example/media/");
        assertThat(media.directory().resolve(cached.imageName())).exists();
        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));
        assertThat(requests.get("/blob.png")).hasValue(1);

        // An edit that names another image for it replaces the copy.
        remoteStatuses.updateRemote(new RemoteEdit(noteUri, author.id(), "<p>:blob:</p>", null, null, false,
                Instant.now(), List.of(), List.of(), null,
                List.of(new RemoteEmoji("blob", null, fileUrl("/blob2.png"), null))));
        var changed = emojis.findForStatus(statusId).getFirst();
        assertThat(changed.id()).isEqualTo(emoji.id());
        assertThat(changed.url(instance)).isEqualTo(fileUrl("/blob2.png"));
        attachments.processDeletions();
        assertThat(media.directory().resolve(cached.imageName())).doesNotExist();
        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));
        assertThat(emojis.findForStatus(statusId).getFirst().imageName()).isNotNull();
    }

    @Test
    void keepsACopyOfAnAvatarAndReplacesItWhenItChanges() throws Exception {
        serve("/avatar.png", 200, "image/png", TestImages.png(64, 64));
        serve("/avatar2.png", 200, "image/png", TestImages.png(32, 32));
        withImages(fileUrl("/avatar.png"), null);

        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));

        var cached = profiles.find(author.id()).avatar();
        assertThat(cached.url(instance, false).toString()).startsWith("https://downloads.example/media/");
        var file = media.directory().resolve(cached.path().substring(cached.path().lastIndexOf('/') + 1));
        assertThat(file).exists();
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        assertThat(requests.get("/avatar.png")).hasValue(1);

        // Fetching the same profile again keeps the copy, and a new image replaces it.
        withImages(fileUrl("/avatar.png"), null);
        assertThat(profiles.find(author.id()).avatar()).isEqualTo(cached);
        withImages(fileUrl("/avatar2.png"), null);
        assertThat(file).doesNotExist();
        assertThat(profiles.find(author.id()).avatar().url(instance, false))
                .isEqualTo(instance.resolve(ProfileImage.proxyPathOf(author.id(), false, false)));
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        assertThat(profiles.find(author.id()).avatar().path()).isNotNull();
    }

    @Test
    void aHeaderThatIsntAnImageStaysALink() throws Exception {
        serve("/header.png", 200, "image/png", "not an image".getBytes());
        withImages(null, fileUrl("/header.png"));

        downloads.run(new ProfileImageDownloadJob(author.id(), true, Instant.now()));

        assertThat(profiles.find(author.id()).header().url(instance, false)).isEqualTo(fileUrl("/header.png"));
        assertThat(profiles.findImageToDownload(author.id(), true)).isEmpty();
    }

    @Test
    void rejectingAnAccountsMediaRemovesItsProfileImageCopies() throws Exception {
        serve("/mine.png", 200, "image/png", TestImages.png(10, 10));
        withImages(fileUrl("/mine.png"), null);
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        var path = profiles.find(author.id()).avatar().path();
        var file = media.directory().resolve(path.substring(path.lastIndexOf('/') + 1));

        assertThat(actors.setMediaRejected(author.id(), true)).isTrue();
        rejections.run(new MediaRejectionJob(null, author.id(), Instant.now()));

        assertThat(file).doesNotExist();
        assertThat(profiles.find(author.id()).avatar().url(instance, false)).isEqualTo(fileUrl("/mine.png"));
        assertThat(profiles.findImageToDownload(author.id(), false)).isEmpty();

        actors.setMediaRejected(author.id(), false);
        await().atMost(Duration.ofSeconds(10)).until(() -> profiles.findImageToDownload(author.id(), false).isPresent());
    }

    @Test
    void rejectingAServersMediaRemovesItsProfileImageCopies() throws Exception {
        serve("/theirs.png", 200, "image/png", TestImages.png(10, 10));
        withImages(fileUrl("/theirs.png"), null);
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        var path = profiles.find(author.id()).avatar().path();
        var file = media.directory().resolve(path.substring(path.lastIndexOf('/') + 1));

        var block = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        rejections.run(new MediaRejectionJob(block.id(), null, Instant.now()));

        assertThat(file).doesNotExist();
        assertThat(profiles.find(author.id()).avatar().path()).isNull();
        // Nothing more is downloaded from it.
        withImages(fileUrl("/theirs.png"), null);
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        assertThat(requests.get("/theirs.png")).hasValue(1);

        blocks.delete(block.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> profiles.findImageToDownload(author.id(), false).isPresent());
    }

    @Test
    void anEmojiPastItsServersBudgetIsTriedAgainWhenItsUsedAgain() throws Exception {
        // Leaves the server one byte of its budget, which lets the emoji be fetched but not kept.
        serve("/most.mp4", 200, "video/mp4", TestMedia.mp4(50 * 1024 - 1));
        serve("/later.png", 200, "image/png", TestImages.png(200, 200));
        downloads.run(new MediaDownloadJob(attach("/most.mp4", "video/mp4").id(), Instant.now()));
        var statusId = remoteStatuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/notes/" + UUID.randomUUID()),
                null, author.id(), "<p>:later:</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null,
                Map.of(), List.of(), List.of(), null, null, null,
                List.of(new RemoteEmoji("later", null, fileUrl("/later.png"), null)))).orElseThrow();
        var emoji = emojis.findForStatus(statusId).getFirst();

        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));

        assertThat(emojis.findForStatus(statusId).getFirst().imageName()).isNull();
        // Unlike an attachment, it isn't given up on, since other statuses keep using it.
        assertThat(emojis.findForDownload(emoji.id())).isPresent();
    }

    @Test
    void lettingAServersMediaThroughAgainLetsItsEmojiBeDownloaded() throws Exception {
        serve("/blocked.png", 200, "image/png", TestImages.png(32, 32));
        var block = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        var statusId = remoteStatuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/notes/" + UUID.randomUUID()),
                null, author.id(), "<p>:blocked:</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null,
                Map.of(), List.of(), List.of(), null, null, null,
                List.of(new RemoteEmoji("blocked", null, fileUrl("/blocked.png"), null)))).orElseThrow();
        var emoji = emojis.findForStatus(statusId).getFirst();
        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));
        assertThat(emojis.findForDownload(emoji.id())).isEmpty();

        blocks.delete(block.id());
        emojis.allowForDomain(host);

        assertThat(emojis.findForDownload(emoji.id())).isPresent();
        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));
        assertThat(emojis.findForStatus(statusId).getFirst().imageName()).isNotNull();
    }

    @Test
    void anUpdateThatOnlyAddsEmojiIsntAnEdit() throws Exception {
        serve("/added.png", 200, "image/png", TestImages.png(32, 32));
        var noteUri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var statusId = remoteStatuses.storeRemote(new RemoteStatus(noteUri, null, author.id(), "<p>:added:</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now().minusSeconds(60))).orElseThrow();

        // As for a status stored before emoji were read, which its server then sends unchanged.
        var outcome = remoteStatuses.updateRemote(new RemoteEdit(noteUri, author.id(), "<p>:added:</p>", null, null, false,
                Instant.now(), List.of(), List.of(), null,
                List.of(new RemoteEmoji("added", null, fileUrl("/added.png"), null))));

        assertThat(outcome).isEqualTo(RemoteEdit.Outcome.UNCHANGED);
        assertThat(emojis.findForStatus(statusId)).extracting(emoji -> emoji.shortcode()).containsExactly("added");
        assertThat(statuses.findHistory(statuses.findVisible(statusId, java.util.Optional.empty()).orElseThrow()))
                .hasSize(1);
    }

    @Test
    void anEmojiThatIsntAnImageStaysALink() {
        serve("/bad.png", 200, "image/png", TestMedia.html());
        var noteUri = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var statusId = remoteStatuses.storeRemote(new RemoteStatus(noteUri, null, author.id(), "<p>:bad:</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(), null, null, null,
                List.of(new RemoteEmoji("bad", null, fileUrl("/bad.png"), null)))).orElseThrow();
        var emoji = emojis.findForStatus(statusId).getFirst();

        downloads.run(new EmojiDownloadJob(emoji.id(), Instant.now()));

        assertThat(emojis.findForStatus(statusId).getFirst().imageName()).isNull();
        assertThat(emojis.findForDownload(emoji.id())).isEmpty();
    }

    private void withImages(URI avatar, URI header) {
        var profile = new RemoteProfile("", "", "", avatar, header, List.of(), false, false, false, false, false, null, null);
        actors.upsertRemote(new RemoteActorProfile(author.uri(), "s", host, author.inboxUri(), null, null, null, null, null,
                profile));
    }

    private URI fileUrl(String path) {
        return URI.create("http://files." + host + ":" + server.getAddress().getPort() + path);
    }

    @Test
    void aFileThatIsntWhatItsServerSaysStaysALink() {
        serve("/clip.mp4", 200, "video/mp4", TestMedia.html());
        var attachment = attach("/clip.mp4", "video/mp4");

        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));

        var found = find(attachment);
        assertThat(found.isCached()).isFalse();
        assertThat(found.type()).isEqualTo(AttachmentType.UNKNOWN);
        assertThat(found.url(instance)).isEqualTo(attachment.remoteUrl());
        assertThat(attachments.findForDownload(attachment.id())).isEmpty();
    }

    @Test
    void aFileThatsTooLargeOrGoneStaysALinkAndIsntTriedAgain() {
        serve("/big.mp4", 200, "video/mp4", TestMedia.mp4(150 * 1024));
        serve("/gone.png", 410, "text/plain", new byte[0]);
        var big = attach("/big.mp4", "video/mp4");
        var gone = attach("/gone.png", "image/png");

        downloads.run(new MediaDownloadJob(big.id(), Instant.now()));
        downloads.run(new MediaDownloadJob(gone.id(), Instant.now()));

        assertThat(find(big).isCached()).isFalse();
        assertThat(find(big).type()).isEqualTo(AttachmentType.VIDEO);
        assertThat(find(gone).isCached()).isFalse();
        assertThat(attachments.findForDownload(big.id())).isEmpty();
        assertThat(attachments.findForDownload(gone.id())).isEmpty();
    }

    @Test
    void aFileThatsTooLargeWithoutALengthIsntTriedAgain() {
        server.createContext("/endless.mp4", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(TestMedia.mp4(200 * 1024));
            } catch (IOException ignored) {
                // The client stops reading.
            }
        });
        var attachment = attach("/endless.mp4", "video/mp4");

        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));

        assertThat(find(attachment).isCached()).isFalse();
        assertThat(attachments.findForDownload(attachment.id())).isEmpty();
    }

    @Test
    void aDownloadLeavesItsHostRoomForOtherRequests() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        server.createContext("/slow.png", exchange -> {
            requests.computeIfAbsent("/slow.png", key -> new AtomicInteger()).incrementAndGet();
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var body = TestImages.png(4, 4);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        serve("/next.png", 200, "image/png", TestImages.png(4, 4));
        var slow = attach("/slow.png", "image/png");
        var next = attach("/next.png", "image/png");
        var fileHost = "files." + host;

        var running = CompletableFuture.runAsync(() -> downloads.run(new MediaDownloadJob(slow.id(), Instant.now())));
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            // A second download from the host waits, and a fetch of a document there still has room.
            downloads.run(new MediaDownloadJob(next.id(), Instant.now()));
            assertThat(requests).doesNotContainKey("/next.png");
            assertThat(hostRequests.tryStartFetch(fileHost)).isTrue();
            assertThat(hostRequests.tryStartFetch(fileHost)).isTrue();
            assertThat(hostRequests.tryStartFetch(fileHost)).isTrue();
            hostRequests.finishFetch(fileHost);
            hostRequests.finishFetch(fileHost);
            hostRequests.finishFetch(fileHost);
        } finally {
            finish.countDown();
        }
        running.get(10, TimeUnit.SECONDS);
        assertThat(find(slow).isCached()).isTrue();
    }

    @Test
    void aCopyIsRemovedWithItsStatus() throws Exception {
        serve("/deleted.png", 200, "image/png", TestImages.png(10, 10));
        serve("/blocked.png", 200, "image/png", TestImages.png(10, 10));
        var status = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var deleted = attach(status, "/deleted.png", "image/png");
        var blocked = attach("/blocked.png", "image/png");
        downloads.run(new MediaDownloadJob(deleted.id(), Instant.now()));
        downloads.run(new MediaDownloadJob(blocked.id(), Instant.now()));
        var deletedFile = media.directory().resolve(find(deleted).fileName());
        var blockedFile = media.directory().resolve(find(blocked).fileName());
        assertThat(deletedFile).exists();

        remoteStatuses.deleteRemote(status, author.uri());

        assertThat(deletedFile).doesNotExist();
        assertThat(blockedFile).exists();

        statuses.removeForDomain(author.domain());

        assertThat(blockedFile).doesNotExist();
    }

    @Test
    void rejectingAServersMediaRemovesItsCopiesAndLinksToThem() throws Exception {
        serve("/kept.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/kept.png", "image/png");
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        var file = media.directory().resolve(find(attachment).fileName());

        var block = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        rejections.run(new MediaRejectionJob(block.id(), null, Instant.now()));

        var rejected = find(attachment);
        assertThat(file).doesNotExist();
        assertThat(rejected.rejected()).isTrue();
        assertThat(rejected.shownType()).isEqualTo(AttachmentType.UNKNOWN);
        assertThat(rejected.url(instance)).isEqualTo(attachment.remoteUrl());

        // Nothing more is downloaded from it.
        serve("/later.png", 200, "image/png", TestImages.png(10, 10));
        var later = attach("/later.png", "image/png");
        downloads.run(new MediaDownloadJob(later.id(), Instant.now()));
        assertThat(requests).doesNotContainKey("/later.png");

        blocks.delete(block.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> !find(attachment).rejected());
        assertThat(find(attachment).shownType()).isEqualTo(AttachmentType.IMAGE);
        assertThat(find(attachment).isCached()).isFalse();
    }

    @Test
    void anotherBlockThatCoversAServerKeepsItsMediaRejected() {
        var subdomain = "social." + host;
        var uri = URI.create("https://" + subdomain + "/users/t");
        var other = actors.upsertRemote(new RemoteActorProfile(uri, "t", subdomain, URI.create(uri + "/inbox"), null, null,
                null, null));
        var status = URI.create(uri + "/notes/" + UUID.randomUUID());
        var id = remoteStatuses.storeRemote(new RemoteStatus(status, null, other.id(), "<p>Hi</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(new RemoteAttachment(URI.create(uri + "/a.png"), AttachmentType.IMAGE, "image/png", null, null,
                        null, null, null)))).orElseThrow();
        var narrow = blocks.create(subdomain, DomainBlock.Severity.NOOP, true, null);
        var broad = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        rejections.run(new MediaRejectionJob(broad.id(), null, Instant.now()));
        assertThat(attachments.findByStatuses(List.of(id)).get(id).getFirst().rejected()).isTrue();

        // What lifting the broad block does, while the narrow one still covers the server, and then the other way round.
        attachments.allowForDomain(host);
        assertThat(attachments.findByStatuses(List.of(id)).get(id).getFirst().rejected()).isTrue();
        blocks.update(broad.id(), DomainBlock.Severity.NOOP, true, null);
        blocks.update(narrow.id(), DomainBlock.Severity.NOOP, false, null);
        attachments.allowForDomain(subdomain);
        assertThat(attachments.findByStatuses(List.of(id)).get(id).getFirst().rejected()).isTrue();

        blocks.update(broad.id(), DomainBlock.Severity.NOOP, false, null);
        attachments.allowForDomain(host);
        assertThat(attachments.findByStatuses(List.of(id)).get(id).getFirst().rejected()).isFalse();
    }

    @Test
    void rejectingMediaQueuesItsJobs() {
        var block = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        actors.setMediaRejected(author.id(), true);

        for (var id : List.of(block.id(), author.id())) {
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                            SELECT count(*) FROM scheduled_tasks
                            WHERE task_name = 'media-rejection' AND convert_from(task_data, 'UTF8') LIKE :id
                            """)
                    .param("id", "%" + id + "%")
                    .query(Long.class)
                    .single() == 2);
        }
    }

    @Test
    void rejectingAnAccountsMediaRemovesItsCopies() throws Exception {
        serve("/mine.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/mine.png", "image/png");
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        var file = media.directory().resolve(find(attachment).fileName());

        assertThat(actors.setMediaRejected(author.id(), true)).isTrue();
        rejections.run(new MediaRejectionJob(null, author.id(), Instant.now()));

        assertThat(file).doesNotExist();
        assertThat(find(attachment).rejected()).isTrue();

        // Its server still rejecting media keeps it rejected when the account is allowed again.
        var block = blocks.create(host, DomainBlock.Severity.NOOP, true, null);
        actors.setMediaRejected(author.id(), false);
        blocks.delete(block.id());
        await().atMost(Duration.ofSeconds(10)).until(() -> !find(attachment).rejected());
    }

    @Test
    void aServerErrorIsTriedAgain() {
        serve("/busy.png", 503, "text/plain", new byte[0]);
        var attachment = attach("/busy.png", "image/png");

        assertThatThrownBy(() -> downloads.run(new MediaDownloadJob(attachment.id(), Instant.now())))
                .hasMessageContaining("503");
        assertThat(attachments.findForDownload(attachment.id())).isPresent();
    }

    @Test
    void aServerCanOnlyHaveSoMuchDownloaded() {
        serve("/first.mp4", 200, "video/mp4", TestMedia.mp4(40 * 1024));
        serve("/second.mp4", 200, "video/mp4", TestMedia.mp4(40 * 1024));
        var first = attach("/first.mp4", "video/mp4");
        var second = attach("/second.mp4", "video/mp4");

        downloads.run(new MediaDownloadJob(first.id(), Instant.now()));
        downloads.run(new MediaDownloadJob(second.id(), Instant.now()));

        assertThat(find(first).isCached()).isTrue();
        assertThat(find(second).isCached()).isFalse();
        // Not given up, so that a view once there's room can keep it.
        assertThat(find(second).url(instance)).isEqualTo(proxy(second, "original"));
        assertThat(attachments.findForDownload(second.id())).isPresent();
    }

    @Test
    void nothingIsDownloadedForAStatusThatsGone() {
        serve("/late.png", 200, "image/png", new byte[0]);
        var status = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var attachment = attach(status, "/late.png", "image/png");
        remoteStatuses.deleteRemote(status, author.uri());

        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));

        assertThat(requests).doesNotContainKey("/late.png");
    }

    @Test
    void anOldCopyIsRemovedAndDownloadedAgainWhenItsViewed() throws Exception {
        serve("/old.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/old.png", "image/png");
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        var cached = find(attachment);
        jdbc.sql("UPDATE media_attachment SET cached_at = now() - interval '31 days' WHERE id = :id")
                .param("id", attachment.id()).update();

        attachments.pruneRemoteCopies();

        var pruned = find(attachment);
        assertThat(pruned.isCached()).isFalse();
        assertThat(pruned.url(instance)).isEqualTo(proxy(attachment, "original"));
        assertThat(pruned.previewUrl(instance)).contains(proxy(attachment, "small"));
        assertThat(media.directory().resolve(cached.fileName())).doesNotExist();
        assertThat(media.directory().resolve(cached.previewName())).doesNotExist();
        assertThat(attachments.findForDownload(attachment.id())).isPresent();
        assertThat(jdbc.sql("SELECT count(*) FROM media_file_deletion").query(Long.class).single()).isZero();
        assertThat(Files.exists(media.directory())).isTrue();

        mvc.perform(get(proxy(attachment, "original")))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", find(attachment).url(instance).toString()));
        var again = find(attachment);
        assertThat(again.isCached()).isTrue();
        assertThat(again.url(instance).toString()).startsWith("https://downloads.example/media/");
        assertThat(media.directory().resolve(again.fileName())).exists();
        assertThat(requests.get("/old.png")).hasValue(2);

        mvc.perform(get(proxy(attachment, "small")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", again.previewUrl(instance).orElseThrow().toString()));
        assertThat(requests.get("/old.png")).hasValue(2);
    }

    @Test
    void theProxyDownloadsAnAttachmentBeforeItsJobRuns() throws Exception {
        serve("/early.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/early.png", "image/png");
        assertThat(attachment.url(instance)).isEqualTo(proxy(attachment, "original"));

        mvc.perform(get(proxy(attachment, "small"))).andExpect(status().isFound());

        assertThat(find(attachment).isCached()).isTrue();
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        assertThat(requests.get("/early.png")).hasValue(1);
    }

    @Test
    void theProxyLinksToWhatCantBeKept() throws Exception {
        serve("/gone.png", 404, "text/plain", new byte[0]);
        var gone = attach("/gone.png", "image/png");

        mvc.perform(get(proxy(gone, "original")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", gone.remoteUrl().toString()));

        var givenUp = find(gone);
        assertThat(givenUp.givenUp()).isTrue();
        assertThat(givenUp.url(instance)).isEqualTo(gone.remoteUrl());
        assertThat(givenUp.previewUrl(instance)).isEmpty();
        mvc.perform(get(proxy(gone, "original"))).andExpect(status().isFound());
        assertThat(requests.get("/gone.png")).hasValue(1);
    }

    @Test
    void theProxyLinksToARejectedAttachment() throws Exception {
        serve("/rejected.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/rejected.png", "image/png");
        blocks.create(host, DomainBlock.Severity.NOOP, true, null);

        mvc.perform(get(proxy(attachment, "original")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", attachment.remoteUrl().toString()));
        assertThat(requests).doesNotContainKey("/rejected.png");
    }

    @Test
    void theProxyDoesntServeWhatsGoneOrWasntAsked() throws Exception {
        serve("/deleted.png", 200, "image/png", TestImages.png(10, 10));
        var status = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var attachment = attach(status, "/deleted.png", "image/png");

        mvc.perform(get(proxy(attachment, "large"))).andExpect(status().isNotFound());
        mvc.perform(get(MediaAttachment.PROXY_PATH + "nope/original")).andExpect(status().isNotFound());
        mvc.perform(get(MediaAttachment.PROXY_PATH + UUID.randomUUID() + "/original")).andExpect(status().isNotFound());

        remoteStatuses.deleteRemote(status, author.uri());
        mvc.perform(get(proxy(attachment, "original"))).andExpect(status().isNotFound());
        assertThat(requests).doesNotContainKey("/deleted.png");
    }

    @Test
    void theProxyLinksPastItsServersBudgetWithoutGivingUp() throws Exception {
        serve("/first.mp4", 200, "video/mp4", TestMedia.mp4(40 * 1024));
        serve("/second.mp4", 200, "video/mp4", TestMedia.mp4(40 * 1024));
        var first = attach("/first.mp4", "video/mp4");
        var second = attach("/second.mp4", "video/mp4");
        downloads.run(new MediaDownloadJob(first.id(), Instant.now()));

        mvc.perform(get(proxy(second, "original")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", second.remoteUrl().toString()));

        assertThat(find(second).isProxied()).isTrue();
        assertThat(attachments.findForDownload(second.id())).isPresent();
    }

    @Test
    void aServerErrorIsntAskedAgainForAWhile() throws Exception {
        serve("/flaky.png", 503, "text/plain", new byte[0]);
        var attachment = attach("/flaky.png", "image/png");

        for (var i = 0; i < 3; i++) {
            mvc.perform(get(proxy(attachment, "original")))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", attachment.remoteUrl().toString()));
        }

        assertThat(requests.get("/flaky.png")).hasValue(1);
        assertThat(find(attachment).isProxied()).isTrue();
    }

    @Test
    void requestsForTheSameFileShareOneDownload() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        server.createContext("/shared.png", exchange -> {
            requests.computeIfAbsent("/shared.png", key -> new AtomicInteger()).incrementAndGet();
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var body = TestImages.png(4, 4);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        var attachment = attach("/shared.png", "image/png");

        var first = CompletableFuture.supplyAsync(() -> downloads.downloadAttachmentNow(attachment.id()));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        var second = CompletableFuture.supplyAsync(() -> downloads.downloadAttachmentNow(attachment.id()));
        var third = CompletableFuture.supplyAsync(() -> downloads.downloadAttachmentNow(attachment.id()));
        Thread.sleep(200);
        finish.countDown();

        for (var request : List.of(first, second, third)) {
            assertThat(request.get(10, TimeUnit.SECONDS).orElseThrow().isCached()).isTrue();
        }
        assertThat(requests.get("/shared.png")).hasValue(1);
    }

    @Test
    void aSlowDownloadLeavesTheRequestLinkedAndCarriesOnForTheNext() throws Exception {
        var finish = new CountDownLatch(1);
        server.createContext("/slow-proxy.png", exchange -> {
            requests.computeIfAbsent("/slow-proxy.png", key -> new AtomicInteger()).incrementAndGet();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var body = TestImages.png(4, 4);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        var attachment = attach("/slow-proxy.png", "image/png");

        try {
            var started = Instant.now();
            mvc.perform(get(proxy(attachment, "original")))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", attachment.remoteUrl().toString()));
            assertThat(Duration.between(started, Instant.now())).isLessThan(MediaDownloads.ON_DEMAND_WAIT.plusSeconds(5));
        } finally {
            finish.countDown();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> find(attachment).isCached());
        assertThat(requests.get("/slow-proxy.png")).hasValue(1);
    }

    @Test
    void anOldAvatarIsDownloadedAgainWhenItsViewed() throws Exception {
        serve("/pruned.gif", 200, "image/gif", TestImages.animatedGif(2, 16, 16));
        withImages(fileUrl("/pruned.gif"), null);
        downloads.run(new ProfileImageDownloadJob(author.id(), false, Instant.now()));
        jdbc.sql("UPDATE profile SET avatar_cached_at = now() - interval '31 days' WHERE actor_id = :id")
                .param("id", author.id()).update();

        profiles.pruneRemoteImages(Instant.now().minus(Duration.ofDays(30)));

        var proxied = profiles.find(author.id()).avatar();
        var path = ProfileImage.proxyPathOf(author.id(), false, false);
        var still = ProfileImage.proxyPathOf(author.id(), false, true);
        assertThat(proxied.url(instance, false)).isEqualTo(instance.resolve(path));
        assertThat(proxied.url(instance, true)).isEqualTo(instance.resolve(still));

        mvc.perform(get(still)).andExpect(status().isFound());
        var kept = profiles.find(author.id()).avatar();
        assertThat(kept.isProxied()).isFalse();
        assertThat(kept.path()).isNotEqualTo(kept.staticPath());
        mvc.perform(get(still))
                .andExpect(header().string("Location", kept.url(instance, true).toString()));
        mvc.perform(get(path))
                .andExpect(header().string("Location", kept.url(instance, false).toString()));
        assertThat(requests.get("/pruned.gif")).hasValue(2);

        mvc.perform(get(ProfileImage.proxyPathOf(author.id(), true, false))).andExpect(status().isNotFound());
        mvc.perform(get(ProfileImage.PROXY_PATH + author.id() + "/banner")).andExpect(status().isNotFound());
    }

    @Test
    void theProxyLinksToAnAvatarThatCantBeKept() throws Exception {
        serve("/text.png", 200, "image/png", "not an image".getBytes());
        withImages(fileUrl("/text.png"), null);

        mvc.perform(get(ProfileImage.proxyPathOf(author.id(), false, false)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", fileUrl("/text.png").toString()));

        assertThat(profiles.find(author.id()).avatar().url(instance, false)).isEqualTo(fileUrl("/text.png"));
    }

    private URI proxy(MediaAttachment attachment, String size) {
        return instance.resolve(MediaAttachment.proxyPathOf(attachment.id(), size));
    }

    @Test
    void storingAStatusQueuesItsDownloads() {
        var attachment = attach("/queued.png", "image/png");

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'media-download' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + attachment.id() + "%")
                .query(Long.class)
                .single() == 1);
    }

    @Test
    void aNewAvatarIsQueuedWhileTheOldOnesJobIsStillWaiting() {
        withImages(fileUrl("/stuck.png"), null);
        awaitProfileImageJobs(1);

        // The old job never runs, as one parked after its host kept failing wouldn't.
        withImages(fileUrl("/fresh.png"), null);
        awaitProfileImageJobs(2);
    }

    private void awaitProfileImageJobs(long count) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'profile-image-download' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + author.id() + "%")
                .query(Long.class)
                .single() == count);
    }

    private MediaAttachment attach(String path, String mediaType) {
        return attach(URI.create(author.uri() + "/notes/" + UUID.randomUUID()), path, mediaType);
    }

    private MediaAttachment attach(URI status, String path, String mediaType) {
        var url = URI.create("http://files." + host + ":" + server.getAddress().getPort() + path);
        var id = remoteStatuses.storeRemote(new RemoteStatus(status, null, author.id(), "<p>Hi</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(new RemoteAttachment(url, mediaType.startsWith("video/") ? AttachmentType.VIDEO : AttachmentType.IMAGE,
                        mediaType, null, null, null, null, null))))
                .orElseThrow();
        return attachments.findByStatuses(List.of(id)).get(id).getFirst();
    }

    private MediaAttachment find(MediaAttachment attachment) {
        return jdbc.sql("SELECT status_id FROM media_attachment WHERE id = :id").param("id", attachment.id())
                .query(UUID.class).optional()
                .map(statusId -> attachments.findByStatuses(List.of(statusId)).get(statusId).stream()
                        .filter(found -> found.id().equals(attachment.id())).findFirst().orElseThrow())
                .orElseThrow();
    }

    private void serve(String path, int status, String contentType, byte[] body) {
        server.createContext(path, exchange -> {
            requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        FederationHttpClient loopbackClient() {
            return LoopbackClients.create();
        }
    }
}
