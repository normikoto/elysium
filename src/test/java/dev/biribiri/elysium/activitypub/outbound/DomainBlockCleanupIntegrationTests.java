package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real scheduler. Each block queues two jobs, and the second is made to run early by moving it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class DomainBlockCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private DomainBlocks blocks;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private StatusService statuses;

    private Actor local;
    private String domain;

    @BeforeEach
    void setUp() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        local = actors.findLocalByUsername(name).orElseThrow();
        domain = "h" + UUID.randomUUID() + ".test";
    }

    @Test
    void removesFollowsInBothDirectionsAndKeepsTheActors() {
        var follower = remote(domain);
        var followed = remote(domain);
        follow(follower, local);
        follow(local, followed);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> follows(follower) == 0 && follows(followed) == 0);
        assertThat(actors.findByUri(follower.uri())).isPresent();
        assertThat(actors.findByUri(followed.uri())).isPresent();
    }

    @Test
    void removesStatusesOfTheBlockedServersActors() {
        var blocked = remote("social." + domain);
        var other = remote("not" + domain);
        var removed = post(blocked);
        var kept = post(other);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> !statusExists(removed));
        assertThat(statusExists(kept)).isTrue();
    }

    @Test
    void coversSubdomainsAndPorts() {
        var onSubdomain = remote("social." + domain);
        var onPort = remote(domain + ":8443");
        follow(onSubdomain, local);
        follow(onPort, local);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> follows(onSubdomain) == 0 && follows(onPort) == 0);
    }

    @Test
    void leavesOtherServersAlone() {
        var blocked = remote(domain);
        var lookalike = remote("not" + domain);
        follow(blocked, local);
        follow(lookalike, local);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> follows(blocked) == 0);
        assertThat(follows(lookalike)).isEqualTo(1);
    }

    @Test
    void theSecondJobRemovesAFollowSavedAfterTheFirst() {
        var follower = remote(domain);
        var block = blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jobs(block.id()) == 1);

        follow(follower, local);
        runRemainingJob(block.id());

        await().atMost(WAIT).until(() -> jobs(block.id()) == 0);
        assertThat(follows(follower)).isZero();
    }

    @Test
    void doesNothingOnceTheBlockIsGone() {
        var follower = remote(domain);
        var block = blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jobs(block.id()) == 1);

        blocks.delete(block.id());
        follow(follower, local);
        runRemainingJob(block.id());

        await().atMost(WAIT).until(() -> jobs(block.id()) == 0);
        assertThat(follows(follower)).isEqualTo(1);
    }

    private UUID post(Actor author) {
        return statuses.storeRemote(new RemoteStatus(
                URI.create(author.uri() + "/notes/" + UUID.randomUUID()), null, author.id(), "<p>Hi</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
    }

    private boolean statusExists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status WHERE id = :id)").param("id", id).query(Boolean.class).single();
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(
                uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private void follow(Actor follower, Actor followed) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :follower, :followed, 'accepted', :activity, now())
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", followed.id())
                .param("activity", "https://example.test/activities/" + UUID.randomUUID())
                .update();
    }

    private long follows(Actor actor) {
        return jdbc.sql("SELECT count(*) FROM follow WHERE follower_id = :id OR followed_id = :id")
                .param("id", actor.id())
                .query(Long.class)
                .single();
    }

    private long jobs(UUID blockId) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'domain-block-cleanup' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + blockId + "%")
                .query(Long.class)
                .single();
    }

    private void runRemainingJob(UUID blockId) {
        jdbc.sql("""
                        UPDATE scheduled_tasks SET execution_time = now()
                        WHERE task_name = 'domain-block-cleanup' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + blockId + "%")
                .update();
    }
}
