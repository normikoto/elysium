package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Blocks of local accounts from other servers. The handlers are called directly with no worker running, as in
 * {@link FollowIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://block.example", "elysium.jobs.worker-enabled=false"})
class BlockIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private BlockHandler blockHandler;
    @Autowired
    private UndoHandler undoHandler;
    @Autowired
    private FollowHandler followHandler;
    @Autowired
    private InboundActivities inbound;
    @Autowired
    private StoredActivities stored;
    @Autowired
    private ActorService actors;
    @Autowired
    private FollowService follows;
    @Autowired
    private LocalRecipients recipients;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor local;
    private String host;
    private String blockerUri;

    @BeforeEach
    void setUp() throws Exception {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        local = actors.findLocalByUsername(name).orElseThrow();
        host = "h" + UUID.randomUUID() + ".test";
        blockerUri = "https://" + host + "/users/1";
        when(documents.fetch(eq(URI.create(blockerUri)))).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"bob\",\"inbox\":\"%s/inbox\"}"
                        .formatted(blockerUri, blockerUri)));
    }

    @Test
    void storesTheBlockAndEndsTheFollowsBothWays() {
        followHandler.handle(follow(newId()));
        var blocker = actors.findByUri(URI.create(blockerUri)).orElseThrow();
        follows.follow(local, blocker);

        blockHandler.handle(block(newId()));

        assertThat(blocks()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE follower_id = :a OR followed_id = :a")
                .param("a", local.id()).query(Long.class).single()).isZero();
        var relationship = follows.relationships(local.id(), List.of(blocker.id())).getFirst();
        assertThat(relationship.blockedBy()).isTrue();
        assertThat(relationship.blocking()).isFalse();
        // The local account's follow is withdrawn, as Mastodon does.
        await().atMost(WAIT).until(() -> outbound().stream().anyMatch(body -> body.contains("\"Undo\"")));
    }

    @Test
    void refusesAFollowFromAnActorTheAccountBlocks() {
        followHandler.handle(follow(newId()));
        var remote = actors.findByUri(URI.create(blockerUri)).orElseThrow();
        jdbc.sql("DELETE FROM follow WHERE followed_id = :id").param("id", local.id()).update();
        jdbc.sql("DELETE FROM outbound_activity WHERE actor_id = :id").param("id", local.id()).update();
        jdbc.sql("""
                        INSERT INTO account_block (id, account_id, target_id, activity_uri)
                        VALUES (gen_random_uuid(), :local, :remote, 'https://block.example/x')
                        """)
                .param("local", local.id()).param("remote", remote.id()).update();

        var followId = newId();
        followHandler.handle(follow(followId));

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", local.id())
                .query(Long.class).single()).isZero();
        var bodies = outbound();
        assertThat(bodies).hasSize(1);
        var reject = json.readTree(bodies.getFirst());
        assertThat(reject.path("type").stringValue()).isEqualTo("Reject");
        assertThat(reject.path("object").path("id").stringValue()).isEqualTo(followId);
    }

    @Test
    void anOlderFollowHandledAfterTheBlockDoesntComeBack() {
        var follow = arrivedAt(follow(newId()), Instant.now().minus(1, ChronoUnit.MINUTES));
        blockHandler.handle(block(newId()));

        followHandler.handle(follow);

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", local.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void anUndoLiftsTheBlock() {
        var blockId = newId();
        var block = arrive(block(blockId));
        blockHandler.handle(block);

        undoHandler.handle(undo("\"" + blockId + "\""));

        assertThat(blocks()).isEmpty();
    }

    @Test
    void anUndoThatEmbedsTheBlockWithoutAnIdLiftsIt() {
        blockHandler.handle(block(newId()));

        undoHandler.handle(undo("{\"type\":\"Block\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(blockerUri, local.uri())));

        assertThat(blocks()).isEmpty();
    }

    @Test
    void anUndoHandledFirstStopsItsBlock() {
        var blockId = newId();
        var block = block(blockId);
        undoHandler.handle(undo(
                "{\"id\":\"%s\",\"type\":\"Block\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(blockId, blockerUri, local.uri())));

        blockHandler.handle(block);

        assertThat(blocks()).isEmpty();
    }

    @Test
    void ignoresAnUndoOfABlockOnAnotherServer() {
        var blockId = newId();
        blockHandler.handle(block(blockId));

        undoHandler.handle(undo("\"https://elsewhere.test/activities/" + UUID.randomUUID() + "\""));

        assertThat(blocks()).hasSize(1);
    }

    @Test
    void ignoresABlockOfAnActorThatIsntLocal() {
        var body = "{\"id\":\"%s\",\"type\":\"Block\",\"actor\":\"%s\",\"object\":\"https://elsewhere.test/users/2\"}"
                .formatted(newId(), blockerUri);

        blockHandler.handle(new InboundActivity(newId(), blockerUri, "Block", body));

        assertThat(jdbc.sql("SELECT count(*) FROM actor WHERE domain = :host").param("host", host)
                .query(Long.class).single()).isZero();
    }

    @Test
    void blockingAgainKeepsOneBlock() {
        blockHandler.handle(block(newId()));
        blockHandler.handle(block(newId()));

        assertThat(blocks()).hasSize(1);
    }

    @Test
    void leavesOutRecipientsThatBlockTheSender() {
        // Stores the remote actor.
        followHandler.handle(follow(newId()));
        var remote = actors.findByUri(URI.create(blockerUri)).orElseThrow();
        jdbc.sql("""
                        INSERT INTO account_block (id, account_id, target_id, activity_uri)
                        VALUES (gen_random_uuid(), :local, :remote, 'https://block.example/y')
                        """)
                .param("local", local.id()).param("remote", remote.id()).update();
        var create = "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"to\":[\"%s\"],\"object\":\"%s/note\"}"
                .formatted(newId(), blockerUri, local.uri(), blockerUri);

        assertThat(recipients.of(new InboundActivity(newId(), blockerUri, "Create", create))).isEmpty();
    }

    private String newId() {
        return "https://" + host + "/activities/" + UUID.randomUUID();
    }

    private InboundActivity block(String id) {
        var body = "{\"id\":\"%s\",\"type\":\"Block\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(id, blockerUri, local.uri());
        return new InboundActivity(id, blockerUri, "Block", body);
    }

    private InboundActivity follow(String id) {
        var body = "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(id, blockerUri, local.uri());
        return new InboundActivity(id, blockerUri, "Follow", body);
    }

    private InboundActivity undo(String object) {
        var id = newId();
        var body = "{\"id\":\"%s\",\"type\":\"Undo\",\"actor\":\"%s\",\"object\":%s}".formatted(id, blockerUri, object);
        return new InboundActivity(id, blockerUri, "Undo", body);
    }

    private InboundActivity arrive(InboundActivity activity) {
        inbound.accept(activity);
        return stored.find(activity.id()).orElseThrow();
    }

    private static InboundActivity arrivedAt(InboundActivity activity, Instant time) {
        return new InboundActivity(activity.id(), activity.actorUri(), activity.type(), activity.body(), time);
    }

    private List<String> blocks() {
        return jdbc.sql("SELECT activity_uri FROM account_block WHERE target_id = :id").param("id", local.id())
                .query(String.class).list();
    }

    private List<String> outbound() {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id ORDER BY created_at")
                .param("id", local.id()).query(String.class).list();
    }
}
