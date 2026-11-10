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
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.media.MediaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

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

/**
 * Downloads other servers' attachments from a server on the loopback address. No job worker runs, so jobs are run
 * directly, apart from the one test that checks one is queued.
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
class MediaDownloadIntegrationTests {

    @Autowired
    private MediaDownloads downloads;
    @Autowired
    private MediaAttachments attachments;
    @Autowired
    private StatusService statuses;
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

        statuses.deleteRemote(status, author.uri());

        assertThat(deletedFile).doesNotExist();
        assertThat(blockedFile).exists();

        statuses.removeForDomain(author.domain());

        assertThat(blockedFile).doesNotExist();
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
        assertThat(find(second).url(instance)).isEqualTo(second.remoteUrl());
    }

    @Test
    void nothingIsDownloadedForAStatusThatsGone() {
        serve("/late.png", 200, "image/png", new byte[0]);
        var status = URI.create(author.uri() + "/notes/" + UUID.randomUUID());
        var attachment = attach(status, "/late.png", "image/png");
        statuses.deleteRemote(status, author.uri());

        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));

        assertThat(requests).doesNotContainKey("/late.png");
    }

    @Test
    void anOldCopyIsRemovedAndTheAttachmentLinkedAgain() throws Exception {
        serve("/old.png", 200, "image/png", TestImages.png(10, 10));
        var attachment = attach("/old.png", "image/png");
        downloads.run(new MediaDownloadJob(attachment.id(), Instant.now()));
        var cached = find(attachment);
        jdbc.sql("UPDATE media_attachment SET cached_at = now() - interval '31 days' WHERE id = :id")
                .param("id", attachment.id()).update();

        attachments.pruneRemoteCopies();

        var pruned = find(attachment);
        assertThat(pruned.isCached()).isFalse();
        assertThat(pruned.url(instance)).isEqualTo(attachment.remoteUrl());
        assertThat(media.directory().resolve(cached.fileName())).doesNotExist();
        assertThat(media.directory().resolve(cached.previewName())).doesNotExist();
        assertThat(attachments.findForDownload(attachment.id())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM media_file_deletion").query(Long.class).single()).isZero();
        assertThat(Files.exists(media.directory())).isTrue();
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

    private MediaAttachment attach(String path, String mediaType) {
        return attach(URI.create(author.uri() + "/notes/" + UUID.randomUUID()), path, mediaType);
    }

    private MediaAttachment attach(URI status, String path, String mediaType) {
        var url = URI.create("http://files." + host + ":" + server.getAddress().getPort() + path);
        var id = statuses.storeRemote(new RemoteStatus(status, null, author.id(), "<p>Hi</p>", null, null,
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
