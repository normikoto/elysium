package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.graph.AccountList;
import dev.biribiri.elysium.graph.AccountLists;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.graph.MuteService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code Move}s from accounts on other servers. The handler is called directly, and the real scheduler runs the jobs it
 * queues, so what they do is waited for.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://move.example",
        "db-scheduler.polling-interval=PT0.1S",
        "db-scheduler.heartbeat-interval=PT1S"})
class MoveIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MoveHandler handler;
    @Autowired
    private RemoteActors remoteActors;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private FollowService follows;
    @Autowired
    private BlockService blocks;
    @Autowired
    private MuteService mutes;
    @Autowired
    private AccountLists lists;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private SchedulerClient scheduler;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor follower;
    private String oldUri;
    private String newUri;
    private Actor old;

    @BeforeEach
    void setUp() throws Exception {
        follower = newLocal();
        oldUri = "https://old" + UUID.randomUUID() + ".test/users/bob";
        newUri = "https://new" + UUID.randomUUID() + ".test/users/bob";
        stub(oldUri, List.of());
        stub(newUri, List.of(oldUri));
        old = remoteActors.fetchOrRefresh(URI.create(oldUri));
        followAccepted(follower, old);
    }

    @Test
    void movesLocalFollowersToTheNewAccountOnceAWeek() {
        var list = lists.create(follower.id(), "Friends", AccountList.RepliesPolicy.LIST, false);
        assertThat(lists.addMembers(follower.id(), list.id(), List.of(old.id()))).isTrue();

        var id = newId();
        handler.handle(move(id, oldUri, newUri));

        await().atMost(WAIT).until(() -> follows(follower.id(), newUri).isPresent());
        assertThat(follows(follower.id(), newUri)).contains("pending");
        assertThat(follows(follower.id(), oldUri)).isEmpty();
        var target = actors.findByUri(URI.create(newUri)).orElseThrow();
        assertThat(lists.containing(follower.id(), target.id())).extracting(AccountList::id).containsExactly(list.id());
        await().atMost(WAIT).until(() -> notifications(follower.id()).contains(target.id()));
        assertThat(profiles.find(old.id()).movedTo()).isEqualTo(URI.create(newUri));
        // The new account is asked, and the old one told the follow is withdrawn.
        await().atMost(WAIT).until(() -> outbound(follower.id()).stream().anyMatch(body -> body.contains("\"Follow\"")
                && body.contains(newUri)));
        assertThat(outbound(follower.id())).anyMatch(body -> body.contains("\"Undo\"") && body.contains(oldUri));
        await().atMost(WAIT).until(() -> moves() == 1);

        // Another move within the week, and the same one again, change nothing.
        var otherUri = "https://other" + UUID.randomUUID() + ".test/users/bob";
        stub(otherUri, List.of(oldUri));
        followAccepted(follower, old);
        var second = newId();
        handler.handle(move(second, oldUri, otherUri));
        handler.handle(move(id, oldUri, newUri));

        await().atMost(WAIT).until(() -> !checkQueued(second) && !checkQueued(id) && !isQueued("follower-move"));
        assertThat(moves()).isOne();
        assertThat(follows(follower.id(), otherUri)).isEmpty();
    }

    @Test
    void findsTheOldAccountAmongMoreAliasesThanAreKept() {
        var aliases = new ArrayList<String>();
        for (var i = 0; i < 30; i++) {
            aliases.add("https://elsewhere.test/users/" + i);
        }
        aliases.add(oldUri);
        stub(newUri, aliases);

        handler.handle(move(newId(), oldUri, newUri));

        await().atMost(WAIT).until(() -> follows(follower.id(), newUri).isPresent());
        assertThat(follows(follower.id(), oldUri)).isEmpty();
    }

    @Test
    void ignoresAMoveToAnAccountThatDoesntListTheOldOne() throws Exception {
        stub(newUri, List.of());

        var id = newId();
        handler.handle(move(id, oldUri, newUri));

        await().atMost(WAIT).until(() -> !checkQueued(id));
        verify(documents).fetch(URI.create(newUri));
        assertThat(follows(follower.id(), oldUri)).contains("accepted");
        assertThat(moves()).isZero();
    }

    @Test
    void ignoresAMoveWhoseObjectIsAnotherAccount() throws Exception {
        var otherUri = "https://other" + UUID.randomUUID() + ".test/users/carol";
        var id = newId();
        var body = "{\"id\":\"%s\",\"type\":\"Move\",\"actor\":\"%s\",\"object\":\"%s\",\"target\":\"%s\"}"
                .formatted(id, oldUri, otherUri, newUri);

        handler.handle(new InboundActivity(id, oldUri, "Move", body));

        assertThat(checkQueued(id)).isFalse();
        verify(documents, never()).fetch(URI.create(newUri));
    }

    @Test
    void doesntFetchTheTargetWhenNobodyHereFollowsBlocksOrMutesTheOldAccount() throws Exception {
        jdbc.sql("DELETE FROM follow WHERE followed_id = :id").param("id", old.id()).update();
        clearInvocations(documents);

        var id = newId();
        handler.handle(move(id, oldUri, newUri));

        assertThat(checkQueued(id)).isFalse();
        verify(documents, never()).fetch(URI.create(newUri));
    }

    @Test
    void movesFollowersToALocalAccountThatListsTheOldOne() {
        var target = newLocal();
        jdbc.sql("""
                        INSERT INTO profile (actor_id, also_known_as) VALUES (:id, ARRAY[:old])
                        ON CONFLICT (actor_id) DO UPDATE SET also_known_as = EXCLUDED.also_known_as
                        """)
                .param("old", oldUri).param("id", target.id()).update();

        handler.handle(move(newId(), oldUri, target.uri().toString()));

        await().atMost(WAIT).until(() -> follows(follower.id(), target.uri().toString()).isPresent());
        assertThat(follows(follower.id(), target.uri().toString())).contains("accepted");
        assertThat(follows(follower.id(), oldUri)).isEmpty();
    }

    @Test
    void keepsTheOldFollowOfAFollowerTheNewAccountBlocks() {
        var target = remoteActors.fetchOrRefresh(URI.create(newUri));
        blocks.receive(target, follower, newId(), Instant.now());

        handler.handle(move(newId(), oldUri, newUri));

        await().atMost(WAIT).until(() -> moves() == 1 && !isQueued("follower-move"));
        assertThat(follows(follower.id(), oldUri)).contains("accepted");
        assertThat(follows(follower.id(), newUri)).isEmpty();
    }

    @Test
    void carriesBlocksAndMutesOverToTheNewAccount() {
        var blocker = newLocal();
        blocks.block(blocker, old);
        var muter = newLocal();
        var expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        mutes.mute(muter.id(), old.id(), false, Optional.of(expiry));

        handler.handle(move(newId(), oldUri, newUri));

        var target = URI.create(newUri);
        await().atMost(WAIT).until(() -> actors.findByUri(target)
                .map(found -> blockExists(blocker.id(), found.id())).orElse(false));
        var targetId = actors.findByUri(target).orElseThrow().id();
        await().atMost(WAIT).until(() -> mutes.expiries(muter.id(), List.of(targetId)).containsKey(targetId));
        assertThat(mutes.expiries(muter.id(), List.of(targetId))).containsEntry(targetId, expiry);
        await().atMost(WAIT).until(() -> outbound(blocker.id()).stream().anyMatch(body -> body.contains("\"Block\"")
                && body.contains(newUri)));
    }

    private Actor newLocal() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        return actors.findLocalByUsername(name).orElseThrow();
    }

    private void stub(String uri, List<String> alsoKnownAs) {
        try {
            when(documents.fetch(eq(URI.create(uri)))).thenReturn(json.readTree("""
                    {"id":"%s","type":"Person","preferredUsername":"bob","inbox":"%s/inbox","alsoKnownAs":%s}
                    """.formatted(uri, uri, json.writeValueAsString(alsoKnownAs))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void followAccepted(Actor local, Actor remote) {
        follows.follow(local, remote);
        jdbc.sql("UPDATE follow SET state = 'accepted' WHERE follower_id = :f AND followed_id = :t")
                .param("f", local.id()).param("t", remote.id()).update();
    }

    private String newId() {
        return URI.create(oldUri).resolve("/activities/" + UUID.randomUUID()).toString();
    }

    private static InboundActivity move(String id, String actor, String target) {
        var body = "{\"id\":\"%s\",\"type\":\"Move\",\"actor\":\"%s\",\"object\":\"%s\",\"target\":\"%s\"}"
                .formatted(id, actor, actor, target);
        return new InboundActivity(id, actor, "Move", body);
    }

    private Optional<String> follows(UUID followerId, String followedUri) {
        return jdbc.sql("""
                        SELECT f.state FROM follow f JOIN actor a ON a.id = f.followed_id
                        WHERE f.follower_id = :f AND a.uri = :uri
                        """)
                .param("f", followerId).param("uri", followedUri).query(String.class).optional();
    }

    private List<UUID> notifications(UUID recipient) {
        return jdbc.sql("SELECT target_id FROM notification WHERE recipient_id = :r AND type = 'move'")
                .param("r", recipient).query(UUID.class).list();
    }

    private long moves() {
        return jdbc.sql("SELECT count(*) FROM account_move WHERE actor_id = :id").param("id", old.id())
                .query(Long.class).single();
    }

    private boolean blockExists(UUID blocker, UUID target) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM account_block WHERE account_id = :a AND target_id = :t)")
                .param("a", blocker).param("t", target).query(Boolean.class).single();
    }

    private boolean checkQueued(String activityId) {
        return Jobs.isQueued(scheduler, MoveChecks.TASK, "move-check", activityId);
    }

    private boolean isQueued(String task) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM scheduled_tasks WHERE task_name = :task)")
                .param("task", task).query(Boolean.class).single();
    }

    private List<String> outbound(UUID actorId) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id ORDER BY created_at")
                .param("id", actorId).query(String.class).list();
    }
}
