package dev.biribiri.elysium.activitypub.outbound;

import com.sun.net.httpserver.HttpServer;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.http.FederationHttpClient;
import dev.biribiri.elysium.activitypub.http.LoopbackClients;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Runs the real listener and scheduler against a real server on the loopback address. Any hostname reaches that server, so
 * each test uses its own hosts and its own author.
 */
@Import({TestcontainersConfiguration.class, StatusDeliveryIntegrationTests.Config.class})
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class StatusDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";
    private static final AtomicInteger next = new AtomicInteger();

    @Autowired
    private StatusService statuses;
    @Autowired
    private LikeService likes;
    @Autowired
    private BoostService boosts;
    @Autowired
    private ActorService actors;
    @MockitoSpyBean
    private FollowService follows;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private EventRetries retries;
    @MockitoBean
    private RemoteActorResolver resolver;

    private final Queue<Received> received = new ConcurrentLinkedQueue<>();
    private final Map<String, CountDownLatch> holdLikes = new ConcurrentHashMap<>();
    private final Set<String> refuseLikes = ConcurrentHashMap.newKeySet();
    private final Map<String, CountDownLatch> holdCreates = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> holdUpdates = new ConcurrentHashMap<>();
    private final Set<String> refuseCreates = ConcurrentHashMap.newKeySet();
    private HttpServer server;
    private Actor author;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var host = exchange.getRequestHeaders().getFirst("Host").split(":")[0];
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    host,
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Signature"),
                    body));
            var hold = body.contains("#updates/") ? holdUpdates.get(host) : holdCreates.get(host);
            if (hold != null && (body.contains("#create") || body.contains("#updates/"))) {
                try {
                    hold.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (json.readTree(body).path("type").asString().equals("Like") && holdLikes.containsKey(host)) {
                try {
                    holdLikes.get(host).await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var refused = refuseCreates.contains(host) && body.contains("#create");
            var retryLike = refuseLikes.contains(host) && json.readTree(body).path("type").asString().equals("Like");
            exchange.sendResponseHeaders(refused ? 410 : retryLike ? 503 : 202, -1);
            exchange.close();
        });
        server.start();
        var username = "author" + next.incrementAndGet();
        actors.createLocalIfAbsent(username, actor -> {
        });
        author = actors.findLocalByUsername(username).orElseThrow();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsAPublicStatusToAFollower() {
        var host = newHost();
        follow(host, "bob", false);

        var status = post(Visibility.PUBLIC, null);

        var create = awaitCreate(host, status);
        assertThat(create.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(create.request().signature()).contains("keyId=\"" + author.uri() + "#main-key\"");
        var body = create.body();
        assertThat(body.path("@context").path(1).path("sensitive").stringValue()).isEqualTo("as:sensitive");
        assertThat(body.path("id").stringValue()).isEqualTo(status.uri() + "#create");
        assertThat(body.path("type").stringValue()).isEqualTo("Create");
        assertThat(body.path("actor").stringValue()).isEqualTo(author.uri().toString());
        var note = body.path("object");
        assertThat(note.has("@context")).isFalse();
        assertThat(note.path("id").stringValue()).isEqualTo(status.uri().toString());
        assertThat(note.path("type").stringValue()).isEqualTo("Note");
        assertThat(note.path("attributedTo").stringValue()).isEqualTo(author.uri().toString());
        assertThat(note.path("content").stringValue()).isEqualTo(status.content());
        assertThat(body.path("published").stringValue()).isEqualTo(note.path("published").stringValue());
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers());
        assertThat(strings(note.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(note.path("cc"))).containsExactly(followers());
    }

    @Test
    void addressesAnUnlistedStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var body = awaitCreate(host, post(Visibility.UNLISTED, null)).body();

        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).containsExactly(PUBLIC);
    }

    @Test
    void addressesAFollowersOnlyStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var body = awaitCreate(host, post(Visibility.PRIVATE, null)).body();

        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).isEmpty();
    }

    @Test
    void aFollowersOnlyReplyLinksToTheAuthorsOwnFollowersOnlyParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.PRIVATE, null);

        var body = awaitCreate(host, post(Visibility.PRIVATE, parent.id())).body();

        assertThat(body.path("object").path("inReplyTo").stringValue()).isEqualTo(parent.uri().toString());
    }

    @Test
    void aPublicReplyDoesNotLinkToAFollowersOnlyParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.PRIVATE, null);

        var body = awaitCreate(host, post(Visibility.PUBLIC, parent.id())).body();

        assertThat(body.path("object").has("inReplyTo")).isFalse();
    }

    @Test
    void aFollowersOnlyReplyDoesNotLinkToADirectParent() {
        var host = newHost();
        follow(host, "bob", false);
        var parent = post(Visibility.DIRECT, null);

        var body = awaitCreate(host, post(Visibility.PRIVATE, parent.id())).body();

        assertThat(body.path("object").has("inReplyTo")).isFalse();
    }

    @Test
    void followersOnAServerShareItsSharedInbox() {
        var host = newHost();
        follow(host, "bob", true);
        follow(host, "carol", true);
        var other = newHost();
        follow(other, "dave", false);

        var status = post(Visibility.PUBLIC, null);

        awaitCreate(host, status);
        awaitCreate(other, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(host).size() == 1);
        assertThat(receivedBy(host).getFirst().path()).isEqualTo("/inbox");
        assertThat(receivedBy(other).getFirst().path()).isEqualTo("/users/dave/inbox");
    }

    @Test
    void sendsADirectStatusOnlyToTheAccountsItMentions() {
        var followerHost = newHost();
        follow(followerHost, "bob", false);
        var host = newHost();
        var carol = mentionable(host, "carol");

        var status = mention(Visibility.DIRECT, host, "carol");

        var create = awaitCreate(host, status);
        assertThat(create.request().path()).isEqualTo("/users/carol/inbox");
        var note = create.body().path("object");
        assertThat(strings(create.body().path("to"))).containsExactly(carol.uri().toString());
        assertThat(strings(create.body().path("cc"))).isEmpty();
        assertThat(strings(note.path("to"))).containsExactly(carol.uri().toString());
        assertThat(strings(note.path("cc"))).isEmpty();
        assertThat(note.path("tag").path(0).path("type").stringValue()).isEqualTo("Mention");
        assertThat(note.path("tag").path(0).path("href").stringValue()).isEqualTo(carol.uri().toString());
        assertThat(note.path("tag").path(0).path("name").stringValue()).isEqualTo("@carol@" + carol.domain());
        awaitNothingLeftFor(status);
        assertThat(receivedBy(followerHost)).isEmpty();
    }

    @Test
    void sendsAPublicStatusToTheAccountsItMentionsAsWellAsFollowers() {
        var followerHost = newHost();
        follow(followerHost, "bob", false);
        var host = newHost();
        var carol = mentionable(host, "carol");

        var status = mention(Visibility.PUBLIC, host, "carol");

        awaitCreate(followerHost, status);
        var body = awaitCreate(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers(), carol.uri().toString());
    }

    @Test
    void sendsADirectReplyToItsParentsAuthorOnlyIfItMentionsThem() {
        var host = newHost();
        var carol = mentionable(host, "carol");
        var parent = statuses.findVisible(remoteStatus(carol, Visibility.PUBLIC), Optional.empty()).orElseThrow();

        var unmentioned = post(Visibility.DIRECT, parent.id());
        var mentioned = statuses.create(author.id(), new NewStatus(
                "@carol@" + host + " hi", null, null, Visibility.DIRECT, false, parent.id()));

        var body = awaitCreate(host, mentioned).body();
        assertThat(strings(body.path("to"))).containsExactly(carol.uri().toString());
        awaitNothingLeftFor(unmentioned);
        assertThat(receivedBy(host).stream().filter(request -> request.body().contains(unmentioned.uri().toString())))
                .isEmpty();
    }

    @Test
    void storesNothingWithoutFollowers() {
        var status = post(Visibility.PUBLIC, null);

        awaitNothingLeftFor(status);
        assertThat(stored(status)).isZero();
    }

    @Test
    void sendsNothingToAFormerFollower() {
        var host = newHost();
        var follower = follow(host, "bob", false);
        follows.undoPair(follower.toString(), author.id(), Instant.now());

        var status = post(Visibility.PUBLIC, null);

        awaitNothingLeftFor(status);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void sendsNothingForARolledBackStatus() {
        var host = newHost();
        follow(host, "bob", false);

        var status = transactions.execute(transaction -> {
            var created = post(Visibility.PUBLIC, null);
            transaction.setRollbackOnly();
            return created;
        });

        assertThat(publications(status)).isZero();
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(host).isEmpty());
        assertThat(stored(status)).isZero();
    }

    @Test
    void retriesAFailedListener() {
        var host = newHost();
        follow(host, "bob", false);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));
        assertThat(receivedBy(host)).isEmpty();

        backdatePublication(status);
        retries.retry();

        awaitCreate(host, status);
        awaitNothingLeftFor(status);
    }

    @Test
    void retriesARetryThatWasAbandoned() {
        var host = newHost();
        follow(host, "bob", false);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));
        backdatePublication(status);
        jdbc.sql("""
                        UPDATE event_publication SET status = 'RESUBMITTED', last_resubmission_date = now() - interval '1 hour'
                        WHERE serialized_event LIKE :id
                        """)
                .param("id", "%" + status.id() + "%")
                .update();

        retries.retry();

        awaitCreate(host, status);
    }

    // Deleting

    @Test
    void sendsAReplyAndItsDeleteToTheRemoteParentsAuthor() {
        var host = newHost();
        var parentAuthor = remoteAuthor(host, "carol");
        var parent = remoteStatus(parentAuthor, Visibility.PUBLIC);

        var reply = post(Visibility.PUBLIC, parent);

        var create = awaitCreate(host, reply);
        assertThat(create.request().path()).isEqualTo("/users/carol/inbox");
        var note = create.body().path("object");
        assertThat(note.path("inReplyTo").stringValue()).contains("/notes/");
        assertThat(strings(note.path("cc"))).contains(parentAuthor.uri().toString());

        statuses.delete(reply.id(), author.id());

        assertThat(awaitDelete(host, reply).request().path()).isEqualTo("/users/carol/inbox");
    }

    @Test
    void sendsNeitherAReplyNorItsDeleteToAuthorsOfParentsThatArentShown() {
        var host = newHost();
        var parentAuthor = remoteAuthor(host, "carol");
        var parent = remoteStatus(parentAuthor, Visibility.PRIVATE);
        // So that the author can see the followers-only parent, and reply to it.
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", author.id())
                .param("followed", parentAuthor.id())
                .param("activity", author.uri() + "#follows/" + UUID.randomUUID())
                .update();
        var followerHost = newHost();
        follow(followerHost, "bob", false);
        var reply = post(Visibility.PUBLIC, parent);
        awaitCreate(followerHost, reply);

        statuses.delete(reply.id(), author.id());

        awaitDelete(followerHost, reply);
        // Every inbox's job has run, so one for the parent's author would have been sent by now.
        await().atMost(WAIT).until(() -> jobsFor(reply.uri() + "#delete") == 0);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void doesNotSendAFollowersOnlyReplyToTheRemoteParentsAuthor() {
        var host = newHost();
        var parentAuthor = remoteAuthor(host, "carol");
        var parent = remoteStatus(parentAuthor, Visibility.PUBLIC);
        var followerHost = newHost();
        follow(followerHost, "bob", false);

        var reply = post(Visibility.PRIVATE, parent);

        awaitCreate(followerHost, reply);
        assertThat(receivedBy(host)).isEmpty();
    }

    @Test
    void sendsADeleteToAFollower() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);

        statuses.delete(status.id(), author.id());

        var delete = awaitDelete(host, status);
        assertThat(delete.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(delete.request().signature()).contains("keyId=\"" + author.uri() + "#main-key\"");
        var body = delete.body();
        assertThat(body.path("@context").stringValue()).isEqualTo("https://www.w3.org/ns/activitystreams");
        assertThat(body.path("id").stringValue()).isEqualTo(status.uri() + "#delete");
        assertThat(body.path("type").stringValue()).isEqualTo("Delete");
        assertThat(body.path("actor").stringValue()).isEqualTo(author.uri().toString());
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers());
        var tombstone = body.path("object");
        assertThat(tombstone.has("@context")).isFalse();
        assertThat(tombstone.path("id").stringValue()).isEqualTo(status.uri().toString());
        assertThat(tombstone.path("type").stringValue()).isEqualTo("Tombstone");
        assertThat(tombstone.path("formerType").stringValue()).isEqualTo("Note");
    }

    @Test
    void addressesTheDeleteOfAnUnlistedStatus() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.UNLISTED, null);

        statuses.delete(status.id(), author.id());

        var body = awaitDelete(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).containsExactly(PUBLIC);
    }

    @Test
    void addressesTheDeleteOfAFollowersOnlyStatus() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PRIVATE, null);

        statuses.delete(status.id(), author.id());

        var body = awaitDelete(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(followers());
        assertThat(strings(body.path("cc"))).isEmpty();
    }

    @Test
    void followersOnAServerShareOneDelete() {
        var host = newHost();
        follow(host, "bob", true);
        follow(host, "carol", true);
        var status = post(Visibility.PUBLIC, null);

        statuses.delete(status.id(), author.id());

        assertThat(awaitDelete(host, status).request().path()).isEqualTo("/inbox");
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> deletesFor(host, status).size() == 1);
    }

    @Test
    void aLaterFollowerIsNotToldAboutADeletedFollowersOnlyStatus() {
        var earlier = newHost();
        follow(earlier, "bob", false);
        var status = post(Visibility.PRIVATE, null);
        awaitCreate(earlier, status);
        awaitNothingLeftFor(status);
        var later = newHost();
        follow(later, "carol", false);

        statuses.delete(status.id(), author.id());

        awaitDelete(earlier, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(later).isEmpty());
    }

    @Test
    void aLaterFollowerIsToldAboutADeletedPublicStatus() {
        var status = post(Visibility.PUBLIC, null);
        awaitNothingLeftFor(status);
        var later = newHost();
        follow(later, "carol", false);

        statuses.delete(status.id(), author.id());

        awaitDelete(later, status);
    }

    @Test
    void aFollowersOnlyStatusIsNotSentToSomeoneWhoFollowedAfterItWasPosted() {
        var earlier = newHost();
        follow(earlier, "bob", false);
        var later = newHost();
        // Runs in the listener, so this follow is after the status was posted, and before its audience is worked out.
        doAnswer(call -> {
            follow(later, "carol", false);
            return call.callRealMethod();
        }).doCallRealMethod().when(follows).followerInboxesAsOf(eq(author.id()), any());

        var status = post(Visibility.PRIVATE, null);

        awaitCreate(earlier, status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(later).isEmpty());
    }

    @Test
    void aDirectReplyLinksToADirectParentWhoseAuthorItMentions() {
        var host = newHost();
        var carol = mentionable(host, "carol");
        var parentId = statuses.storeRemote(new RemoteStatus(
                URI.create(carol.uri() + "/notes/" + UUID.randomUUID()), null, carol.id(), "<p>Psst</p>", null, null,
                Visibility.DIRECT, false, null, Instant.now(), null, Map.of(author.id(), false))).orElseThrow();
        var parent = statuses.findVisible(parentId, Optional.of(author.id())).orElseThrow();

        var reply = statuses.create(author.id(), new NewStatus(
                "@carol@" + host + " hi", null, null, Visibility.DIRECT, false, parent.id()));

        var note = awaitCreate(host, reply).body().path("object");
        assertThat(note.path("inReplyTo").stringValue()).isEqualTo(parent.uri().toString());
        assertThat(strings(note.path("to"))).containsExactly(carol.uri().toString());
    }

    @Test
    void aDirectReplyDoesNotLinkToADirectParentWhoseAuthorItDoesNotMention() {
        var host = newHost();
        var carol = mentionable(host, "carol");
        var dave = mentionable(host, "dave");
        var parentId = statuses.storeRemote(new RemoteStatus(
                URI.create(carol.uri() + "/notes/" + UUID.randomUUID()), null, carol.id(), "<p>Psst</p>", null, null,
                Visibility.DIRECT, false, null, Instant.now(), null, Map.of(author.id(), false))).orElseThrow();

        var reply = statuses.create(author.id(), new NewStatus(
                "@dave@" + host + " look", null, null, Visibility.DIRECT, false, parentId));

        var note = awaitCreate(host, reply).body().path("object");
        assertThat(note.has("inReplyTo")).isFalse();
        assertThat(strings(note.path("to"))).containsExactly(dave.uri().toString());
    }

    @Test
    void sendsNothingForADeletedDirectStatusThatMentionedNobody() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.DIRECT, null);

        statuses.delete(status.id(), author.id());

        awaitNothingLeftFor(status);
        assertThat(receivedBy(host)).isEmpty();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void sendsTheDeleteOfADirectStatusToTheAccountsItMentioned() {
        var followerHost = newHost();
        follow(followerHost, "bob", false);
        var host = newHost();
        var carol = mentionable(host, "carol");
        var status = mention(Visibility.DIRECT, host, "carol");
        awaitCreate(host, status);

        statuses.delete(status.id(), author.id());

        var body = awaitDelete(host, status).body();
        assertThat(strings(body.path("to"))).containsExactly(carol.uri().toString());
        assertThat(strings(body.path("cc"))).isEmpty();
        awaitNothingLeftFor(status);
        assertThat(receivedBy(followerHost)).isEmpty();
    }

    @Test
    void cancelsAnUnsentCreateEvenWithNoFollowersLeft() {
        var status = post(Visibility.PUBLIC, null);
        awaitNothingLeftFor(status);
        jdbc.sql("INSERT INTO outbound_activity (activity_id, actor_id, body) VALUES (:id, :actorId, '{}')")
                .param("id", status.uri() + "#create")
                .param("actorId", author.id())
                .update();

        statuses.delete(status.id(), author.id());

        awaitNothingLeftFor(status);
        assertThat(stored(status)).isZero();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void sendsNothingForARolledBackDelete() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        awaitNothingLeftFor(status);

        transactions.executeWithoutResult(transaction -> {
            statuses.delete(status.id(), author.id());
            transaction.setRollbackOnly();
        });

        assertThat(statuses.findVisible(status.id(), Optional.empty())).isPresent();
        assertThat(statuses.findTombstone(status.id(), author.id())).isEmpty();
        assertThat(publications(status)).isZero();
        assertThat(storedDelete(status)).isZero();
    }

    @Test
    void retriesAFailedDeleteListener() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        awaitNothingLeftFor(status);
        doThrow(new IllegalStateException("Expected by this test"))
                .doCallRealMethod()
                .when(follows).followerInboxes(author.id());

        statuses.delete(status.id(), author.id());
        await().atMost(WAIT).until(() -> "FAILED".equals(publicationStatus(status)));

        backdatePublication(status);
        retries.retry();

        awaitDelete(host, status);
        awaitNothingLeftFor(status);
    }

    @Test
    void aDeleteWaitsForItsCreateToBeQueuedAndThenCancelsIt() {
        var host = newHost();
        follow(host, "bob", false);
        var posted = new CompletableFuture<Status>();
        var deleting = new CompletableFuture<CompletableFuture<?>>();
        var deleteWaited = new AtomicBoolean();
        // Only the Create's listener gets this. By then, the status is held until that listener commits.
        doAnswer(call -> {
            var status = posted.get(30, TimeUnit.SECONDS);
            var delete = CompletableFuture.runAsync(() -> statuses.delete(status.id(), author.id()));
            deleting.complete(delete);
            try {
                delete.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                deleteWaited.set(true);
            }
            return call.callRealMethod();
        }).doCallRealMethod().when(follows).followerInboxes(author.id());

        var status = post(Visibility.PUBLIC, null);
        posted.complete(status);

        await().atMost(WAIT).until(() -> deleting.isDone() && deleting.join().isDone());
        assertThat(deleteWaited).isTrue();
        await().atMost(WAIT).until(() -> stored(status) == 0);
        awaitDelete(host, status);
    }

    @Test
    void aDeleteStaysBehindACreateBeingSent() throws Exception {
        var host = newHost();
        follow(host, "bob", false);
        var hold = holdCreates(host);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);

        try {
            statuses.delete(status.id(), author.id());

            await().atMost(WAIT).until(() -> jobsFor(status.uri() + "#delete") == 1);
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> deletesFor(host, status).isEmpty());
        } finally {
            hold.countDown();
        }
        awaitDelete(host, status);
    }

    @Test
    void aDeleteStaysBehindACreateBeingSentToAnotherInboxOnItsServer() throws Exception {
        var host = newHost();
        var follower = follow(host, "bob", false);
        var hold = holdCreates(host);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        var domain = follower.getAuthority();

        try {
            actors.upsertRemote(new RemoteActorProfile(
                    follower,
                    "bob",
                    domain,
                    URI.create(follower + "/inbox"),
                    null,
                    URI.create("http://" + domain + "/inbox"),
                    null));
            statuses.delete(status.id(), author.id());

            await().atMost(WAIT).until(() -> jobsFor(status.uri() + "#delete") == 1);
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> deletesFor(host, status).isEmpty());
        } finally {
            hold.countDown();
        }
        assertThat(awaitDelete(host, status).request().path()).isEqualTo("/inbox");
    }

    @Test
    void aDeleteOnlyWaitsForItsOwnServer() throws Exception {
        var held = newHost();
        follow(held, "bob", false);
        var other = newHost();
        follow(other, "carol", false);
        var hold = holdCreates(held);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(held, status);
        awaitCreate(other, status);

        try {
            statuses.delete(status.id(), author.id());

            awaitDelete(other, status);
            assertThat(deletesFor(held, status)).isEmpty();
        } finally {
            hold.countDown();
        }
        awaitDelete(held, status);
    }

    @Test
    void aDeleteDoesNotWaitForACreateThatGaveUp() {
        var host = newHost();
        follow(host, "bob", false);
        refuseCreates.add(host);
        var status = post(Visibility.PUBLIC, null);
        await().atMost(WAIT).until(() -> parkedJobsFor(status.uri() + "#create") == 1);

        statuses.delete(status.id(), author.id());

        awaitDelete(host, status);
        assertThat(jobsFor(status.uri() + "#create")).isOne();
    }

    // Helpers

    @Test
    void sendsAnEditToFollowers() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);

        var edited = edit(status, "Hello again");

        var update = awaitUpdate(host, status);
        assertThat(update.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(update.request().signature()).contains("keyId=\"" + author.uri() + "#main-key\"");
        var body = update.body();
        assertThat(body.path("@context").path(1).path("sensitive").stringValue()).isEqualTo("as:sensitive");
        assertThat(body.path("id").stringValue())
                .isEqualTo(status.uri() + "#updates/" + edited.editedAt().toEpochMilli());
        assertThat(body.path("type").stringValue()).isEqualTo("Update");
        assertThat(body.path("actor").stringValue()).isEqualTo(author.uri().toString());
        assertThat(strings(body.path("to"))).containsExactly(PUBLIC);
        assertThat(strings(body.path("cc"))).containsExactly(followers());
        var note = body.path("object");
        assertThat(note.has("@context")).isFalse();
        assertThat(note.path("id").stringValue()).isEqualTo(status.uri().toString());
        assertThat(note.path("content").stringValue()).isEqualTo("<p>Hello again</p>");
        assertThat(Instant.parse(note.path("updated").stringValue()))
                .isAfter(Instant.parse(note.path("published").stringValue()));
    }

    @Test
    void sendsAnEditToTheAccountsItMentionsAndThoseItNoLongerDoes() {
        var before = newHost();
        mentionable(before, "carol");
        var after = newHost();
        var dave = mentionable(after, "dave");
        var status = mention(Visibility.DIRECT, before, "carol");
        awaitCreate(before, status);

        edit(status, "Hello @dave@" + after);

        var note = awaitUpdate(after, status).body().path("object");
        assertThat(strings(note.path("to"))).containsExactly(dave.uri().toString());
        assertThat(note.path("tag").path(0).path("href").stringValue()).isEqualTo(dave.uri().toString());
        assertThat(awaitUpdate(before, status).request().path()).isEqualTo("/users/carol/inbox");
    }

    @Test
    void sendsAnEditToTheFollowersOfLocalAccountsThatBoostedIt() {
        var status = post(Visibility.PUBLIC, null);
        var booster = actors.createLocalIfAbsent("booster" + next.incrementAndGet(), actor -> {
        }).orElseThrow();
        var host = newHost();
        follow(host, "bob", false, booster);
        boosts.reblog(status.id(), booster.id(), Visibility.PUBLIC).orElseThrow();
        awaitNothingLeftFor(status);

        edit(status, "Hello again");

        awaitUpdate(host, status);
    }

    @Test
    void doesNotSendTheEditOfAFollowersOnlyStatusToSomeoneWhoFollowedAfterItWasPosted() {
        var earlier = newHost();
        follow(earlier, "bob", false);
        var status = post(Visibility.PRIVATE, null);
        awaitCreate(earlier, status);
        var later = newHost();
        follow(later, "carol", false);

        edit(status, "Hello again");

        awaitUpdate(earlier, status);
        awaitNothingLeftFor(status);
        await().during(Duration.ofSeconds(1)).atMost(WAIT).until(() -> receivedBy(later).isEmpty());
    }

    @Test
    void aQueuedCreateCarriesTheEdit() {
        var status = post(Visibility.PUBLIC, null);
        awaitNothingLeftFor(status);
        // As if its Create were still waiting to be sent.
        jdbc.sql("INSERT INTO outbound_activity (activity_id, actor_id, body) VALUES (:id, :actorId, '{}')")
                .param("id", status.uri() + "#create")
                .param("actorId", author.id())
                .update();

        edit(status, "Hello again");

        await().atMost(WAIT).until(() -> createBody(status).contains("Hello again"));
        var create = json.readTree(createBody(status));
        assertThat(create.path("type").stringValue()).isEqualTo("Create");
        assertThat(create.path("object").path("updated").isString()).isTrue();
    }

    @Test
    void aDeleteStaysBehindAnUpdateBeingSent() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        var hold = new CountDownLatch(1);
        holdUpdates.put(host, hold);
        edit(status, "Hello again");
        awaitUpdate(host, status);

        try {
            statuses.delete(status.id(), author.id());

            await().atMost(WAIT).until(() -> jobsFor(status.uri() + "#delete") == 1);
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> deletesFor(host, status).isEmpty());
        } finally {
            hold.countDown();
        }
        awaitDelete(host, status);
    }

    @Test
    void anEditIsDroppedIfItsAuthorIsSuspendedBeforeItsSent() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);

        var edited = edit(status, "Hello again");

        var update = status.uri() + "#updates/" + edited.editedAt().toEpochMilli();
        await().atMost(WAIT).until(() -> jdbc.sql(
                        "SELECT count(*) FROM outbound_activity WHERE activity_id = :id AND while_suspended = 'DROP'")
                .param("id", update).query(Long.class).single() == 1);
    }

    @Test
    void aDeleteCancelsAQueuedEdit() {
        var host = newHost();
        follow(host, "bob", false);
        var status = post(Visibility.PUBLIC, null);
        awaitCreate(host, status);
        awaitNothingLeftFor(status);
        var update = status.uri() + "#updates/1";
        jdbc.sql("INSERT INTO outbound_activity (activity_id, actor_id, body) VALUES (:id, :actorId, '{}')")
                .param("id", update)
                .param("actorId", author.id())
                .update();

        statuses.delete(status.id(), author.id());

        awaitDelete(host, status);
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", update).query(Long.class).single()).isZero();
    }

    @Test
    void sendsSignedLikesAndUndosToTheRemoteAuthorsPersonalInbox() {
        var host = newHost();
        var remote = remoteAuthor(host, "bob");
        var status = remoteStatus(remote, Visibility.PUBLIC);
        likes.favourite(status, author.id());
        var saved = likes.page(status, null, 10).getFirst();
        var sent = awaitActivity(host, saved.activityUri());
        assertThat(sent.request().path()).isEqualTo("/users/bob/inbox");
        assertThat(sent.request().signature()).contains("keyId=", "rsa-sha256");
        assertThat(sent.body().path("type").asString()).isEqualTo("Like");
        assertThat(sent.body().path("actor").asString()).isEqualTo(author.uri().toString());
        likes.unfavourite(status, author.id());
        var undo = awaitActivity(host, saved.activityUri() + "/undo");
        assertThat(undo.request().signature()).isNotBlank();
        assertThat(undo.body().path("type").asString()).isEqualTo("Undo");
        assertThat(undo.body().path("object").path("id").asString()).isEqualTo(saved.activityUri());
    }

    @Test
    void anUnlikeWaitsForAnInFlightLike() {
        var host = newHost();
        var hold = new CountDownLatch(1);
        holdLikes.put(host, hold);
        var remote = remoteAuthor(host, "bob");
        var status = remoteStatus(remote, Visibility.PUBLIC);
        likes.favourite(status, author.id());
        var saved = likes.page(status, null, 10).getFirst();
        awaitActivity(host, saved.activityUri());
        try {
            likes.unfavourite(status, author.id());
            await().atMost(WAIT).until(() -> jobsFor(saved.activityUri() + "/undo") == 1);
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> receivedBy(host).stream()
                    .noneMatch(request -> json.readTree(request.body()).path("type").asString().equals("Undo")));
        } finally {
            hold.countDown();
        }
        awaitActivity(host, saved.activityUri() + "/undo");
    }

    @Test
    void retriesLikeDeliveryAfterAServerFailure() {
        var host = newHost();
        refuseLikes.add(host);
        var remote = remoteAuthor(host, "bob");
        var status = remoteStatus(remote, Visibility.PUBLIC);
        likes.favourite(status, author.id());
        var saved = likes.page(status, null, 10).getFirst();
        awaitActivity(host, saved.activityUri());
        await().atMost(WAIT).until(() -> parkedJobsFor(saved.activityUri()) == 1);
        refuseLikes.remove(host);
        jdbc.sql("UPDATE scheduled_tasks SET execution_time = now() WHERE task_name = 'activity-delivery' "
                        + "AND convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + saved.activityUri() + "%").update();
        await().atMost(WAIT).until(() -> receivedBy(host).size() >= 2);
        await().atMost(WAIT).until(() -> jobsFor(saved.activityUri()) == 0);
        assertThat(receivedBy(host).stream().map(Received::body).distinct()).hasSize(1);
    }

    private String newHost() {
        return "statuses" + next.incrementAndGet() + ".test";
    }

    private URI follow(String host, String username, boolean sharedInbox) {
        return follow(host, username, sharedInbox, author);
    }

    private URI follow(String host, String username, boolean sharedInbox, Actor followed) {
        var domain = host + ":" + server.getAddress().getPort();
        var uri = URI.create("http://" + domain + "/users/" + username);
        var follower = actors.upsertRemote(new RemoteActorProfile(
                uri,
                username,
                domain,
                URI.create(uri + "/inbox"),
                null,
                sharedInbox ? URI.create("http://" + domain + "/inbox") : null,
                null));
        var now = Instant.now();
        transactions.executeWithoutResult(transaction -> follows.accept(
                follower.id(), uri, followed.id(), uri + "#follow", new FollowService.Arrival(now, now)));
        return uri;
    }

    /**
     * An account on another server that a status can mention as {@code @username@host}, which WebFinger would find.
     */
    private Actor mentionable(String host, String username) {
        var actor = remoteAuthor(host, username);
        when(resolver.resolveAcct(any(), eq(username), eq(host))).thenReturn(Optional.of(actor));
        return actor;
    }

    private Status mention(Visibility visibility, String host, String username) {
        return statuses.create(author.id(), new NewStatus(
                "Hello @" + username + "@" + host, null, null, visibility, false, null));
    }

    private Status post(Visibility visibility, UUID inReplyTo) {
        return statuses.create(author.id(), new NewStatus("Hello", null, null, visibility, false, inReplyTo));
    }

    private Actor remoteAuthor(String host, String username) {
        var domain = host + ":" + server.getAddress().getPort();
        var uri = URI.create("http://" + domain + "/users/" + username);
        return actors.upsertRemote(
                new RemoteActorProfile(uri, username, domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private UUID remoteStatus(Actor remote, Visibility visibility) {
        return statuses.storeRemote(new RemoteStatus(
                URI.create(remote.uri() + "/notes/" + UUID.randomUUID()), null, remote.id(), "<p>Hi</p>", null, null,
                visibility, false, null, Instant.now())).orElseThrow();
    }

    private String followers() {
        return author.uri() + "/followers";
    }

    private Delivered awaitCreate(String host, Status status) {
        return awaitActivity(host, status.uri() + "#create");
    }

    private CountDownLatch holdCreates(String host) {
        var hold = new CountDownLatch(1);
        holdCreates.put(host, hold);
        return hold;
    }

    private long parkedJobsFor(String activityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND consecutive_failures > 0 AND NOT picked
                          AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activityId + "%")
                .query(Long.class)
                .single();
    }

    private long jobsFor(String activityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + activityId + "%")
                .query(Long.class)
                .single();
    }

    private Status edit(Status status, String text) {
        return statuses.edit(status.id(), author.id(), new StatusEdit(text, null, null, null)).orElseThrow();
    }

    private Delivered awaitUpdate(String host, Status status) {
        return awaitActivity(host, status.uri() + "#updates/");
    }

    private String createBody(Status status) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE activity_id = :id")
                .param("id", status.uri() + "#create")
                .query(String.class)
                .single();
    }

    private Delivered awaitDelete(String host, Status status) {
        return awaitActivity(host, status.uri() + "#delete");
    }

    private Delivered awaitActivity(String host, String activityId) {
        await().atMost(WAIT).until(() -> receivedBy(host).stream()
                .anyMatch(request -> request.body().contains(activityId)));
        var request = receivedBy(host).stream()
                .filter(candidate -> candidate.body().contains(activityId))
                .findFirst()
                .orElseThrow();
        return new Delivered(request, json.readTree(request.body()));
    }

    private List<Received> deletesFor(String host, Status status) {
        return receivedBy(host).stream().filter(request -> request.body().contains(status.uri() + "#delete")).toList();
    }

    private void awaitNothingLeftFor(Status status) {
        await().atMost(WAIT).until(() -> publications(status) == 0);
    }

    private List<Received> receivedBy(String host) {
        return received.stream().filter(request -> request.host().equals(host)).toList();
    }

    private static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::stringValue).toList();
    }

    private long stored(Status status) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", status.uri() + "#create")
                .query(Long.class)
                .single();
    }

    private long storedDelete(Status status) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id")
                .param("id", status.uri() + "#delete")
                .query(Long.class)
                .single();
    }

    private long publications(Status status) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .query(Long.class)
                .single();
    }

    private String publicationStatus(Status status) {
        return jdbc.sql("SELECT status FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void backdatePublication(Status status) {
        jdbc.sql("UPDATE event_publication SET publication_date = now() - interval '1 hour' WHERE serialized_event LIKE :id")
                .param("id", "%" + status.id() + "%")
                .update();
    }

    private record Received(String host, String path, String signature, String body) {
    }

    private record Delivered(Received request, JsonNode body) {
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
