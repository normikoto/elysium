package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real scheduler, and looks at what's queued rather than what's received, which
 * {@code ActivityDeliveryIntegrationTests} covers. Each change queues two jobs, and the second is made to run early by moving it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class AccountCleanupIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ApplicationEventPublisher events;

    private Actor local;

    @BeforeEach
    void setUp() {
        local = newLocal();
    }

    @Test
    void suspendingALocalAccountKeepsEverythingAndTellsFollowers() {
        var follower = remote();
        follow(follower, local);
        var status = statuses.create(local.id(), draft());

        actors.suspend(local.id());

        await().atMost(WAIT).until(() -> !activities(local, "%#suspended/%").isEmpty());
        var update = json.readTree(activities(local, "%#suspended/%").getFirst());
        assertThat(update.get("type").asString()).isEqualTo("Update");
        assertThat(update.get("actor").asString()).isEqualTo(local.uri().toString());
        assertThat(update.get("object").get("suspended").asBoolean()).isTrue();
        assertThat(update.get("object").has("@context")).isFalse();
        assertThat(update.get("object").get("endpoints").get("sharedInbox").asString())
                .isEqualTo(local.uri().resolve("/inbox").toString());
        assertThat(follows(local)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM status WHERE id = :id", status.id())).isOne();

        actors.unsuspend(local.id());

        await().atMost(WAIT).until(() -> !activities(local, "%#unsuspended/%").isEmpty());
        var back = json.readTree(activities(local, "%#unsuspended/%").getFirst());
        assertThat(back.get("object").has("suspended")).isFalse();
        assertThat(statuses.findVisible(status.id(), Optional.empty())).isPresent();
    }

    @Test
    void suspendingARemoteActorRemovesItsFollowsAndKeepsItsRow() {
        var suspended = remote();
        follow(suspended, local);
        follow(local, suspended);

        actors.suspend(suspended.id());

        await().atMost(WAIT).until(() -> follows(suspended) == 0);
        assertThat(actors.findByUri(suspended.uri()).orElseThrow().isSuspended()).isTrue();
        assertThat(activities(suspended, "%")).isEmpty();
    }

    @Test
    void refreshingARemoteActorDoesNotEndItsSuspension() {
        var suspended = remote();
        actors.suspend(suspended.id());

        actors.upsertRemote(new RemoteActorProfile(
                suspended.uri(), "newname", suspended.domain(), suspended.inboxUri(), null, null, null));

        assertThat(actors.findByUri(suspended.uri()).orElseThrow().isSuspended()).isTrue();
    }

    @Test
    void aFollowSavedAfterTheFirstJobIsRemovedByTheSecond() {
        var suspended = remote();
        actors.suspend(suspended.id());
        await().atMost(WAIT).until(() -> jobs(suspended) == 1);

        follow(suspended, local);
        runRemainingJob(suspended);

        await().atMost(WAIT).until(() -> follows(suspended) == 0);
    }

    @Test
    void aSuspensionThatHasEndedRemovesNothing() {
        var suspended = remote();
        actors.suspend(suspended.id());
        await().atMost(WAIT).until(() -> jobs(suspended) == 1);
        actors.unsuspend(suspended.id());

        follow(suspended, local);
        runRemainingJob(suspended);

        await().atMost(WAIT).until(() -> jobs(suspended) == 0);
        assertThat(follows(suspended)).isEqualTo(1);
    }

    @Test
    void deletingAnAccountTellsFollowersAndThenRemovesItsDataAndKeepsItsRow() {
        var follower = remote();
        follow(follower, local);
        var status = statuses.create(local.id(), draft());
        statuses.delete(statuses.create(local.id(), draft()).id(), local.id());
        // A Create still being sent.
        var queued = "https://elysium.example/activities/" + UUID.randomUUID();
        jdbc.sql("INSERT INTO outbound_activity (activity_id, actor_id, body) VALUES (:id, :actorId, 'secret')")
                .param("id", queued)
                .param("actorId", local.id())
                .update();

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> follows(local) == 0
                && count("SELECT count(*) FROM status WHERE actor_id = :id", local.id()) == 0);
        assertThat(count("SELECT count(*) FROM status WHERE id = :id", status.id())).isZero();
        assertThat(count("SELECT count(*) FROM status_tombstone WHERE actor_id = :id", local.id())).isZero();
        assertThat(actors.findById(local.id())).isPresent();
        var delete = json.readTree(activities(local, local.uri() + "#delete").getFirst());
        assertThat(delete.get("type").asString()).isEqualTo("Delete");
        assertThat(delete.get("object").asString()).isEqualTo(local.uri().toString());
        assertThat(activities(local, queued)).isEmpty();
    }

    @Test
    void deletingAnAccountWithdrawsItsFollowsOfOtherServers() {
        var followed = remote();
        var requested = remote();
        var follower = remote();
        var accepted = sentFollow(followed, "accepted");
        var pending = sentFollow(requested, "pending");
        follow(follower, local);

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> activities(local, "%/undo").size() == 2);
        for (var follow : List.of(accepted, pending)) {
            var undo = json.readTree(activities(local, follow + "/undo").getFirst());
            assertThat(undo.get("type").asString()).isEqualTo("Undo");
            assertThat(undo.get("actor").asString()).isEqualTo(local.uri().toString());
            assertThat(undo.get("object").get("id").asString()).isEqualTo(follow);
        }
    }

    @Test
    void deletingAnAccountCancelsAnUpdateAboutIt() {
        follow(remote(), local);
        actors.suspend(local.id());
        await().atMost(WAIT).until(() -> !activities(local, "%#suspended/%").isEmpty());

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> !activities(local, local.uri() + "#delete").isEmpty());
        assertThat(activities(local, "%#suspended/%")).isEmpty();
    }

    /**
     * The listener can run after its author is suspended, and followers shouldn't keep what was deleted if that ends.
     */
    @Test
    void aStatusDeletedJustBeforeItsAuthorIsSuspendedStillHasItsDeleteQueued() {
        follow(remote(), local);
        var status = statuses.create(local.id(), draft());
        actors.suspend(local.id());

        transactions.executeWithoutResult(tx -> events.publishEvent(new StatusDeleted(
                status.id(), status.uri(), local.id(), status.visibility(), status.publishedAt(), null)));

        await().atMost(WAIT).until(() -> !activities(local, status.uri() + "#delete").isEmpty());
    }

    @Test
    void aStatusOfADeletedAuthorGetsNoDeleteOfItsOwn() {
        follow(remote(), local);
        var status = statuses.create(local.id(), draft());
        actors.markDeleted(local.id());

        transactions.executeWithoutResult(tx -> events.publishEvent(new StatusDeleted(
                status.id(), status.uri(), local.id(), status.visibility(), status.publishedAt(), null)));

        await().atMost(WAIT).until(() -> publications() == 0);
        assertThat(activities(local, status.uri() + "#delete")).isEmpty();
    }

    @Test
    void aStatusOfOtherAccountsReplyingToADeletedOneStays() {
        var replier = newLocal();
        var parent = statuses.create(local.id(), draft());
        var reply = statuses.create(
                replier.id(), new NewStatus("A reply", "", null, Visibility.PUBLIC, false, parent.id()));

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM status WHERE id = :id", parent.id()) == 0);
        assertThat(statuses.findVisible(reply.id(), Optional.empty())).isPresent();
    }

    @Test
    void removesKeysOnlyOfAccountsDeletedLongEnoughAgo() {
        actors.markDeleted(local.id());
        var recent = newLocal();
        actors.markDeleted(recent.id());
        jdbc.sql("UPDATE actor SET deleted_at = now() - interval '40 days' WHERE id = :id").param("id", local.id()).update();

        actors.removeKeysOfDeleted(Instant.now().minus(Duration.ofDays(35)));

        assertThat(count("SELECT count(*) FROM local_account WHERE actor_id = :id", local.id())).isZero();
        assertThat(count("SELECT count(*) FROM local_account WHERE actor_id = :id", recent.id())).isOne();
        var kept = actors.findAnyLocalById(local.id()).orElseThrow();
        assertThat(kept.publicKeyPem()).isNull();
        assertThat(kept.isDeleted()).isTrue();
        assertThat(actors.findAnyLocalById(recent.id()).orElseThrow().publicKeyPem()).isNotNull();
    }

    private static NewStatus draft() {
        return new NewStatus("Hello", "", null, Visibility.PUBLIC, false, null);
    }

    private Actor newLocal() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        return actors.findLocalByUsername(name).orElseThrow();
    }

    private Actor remote() {
        var domain = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));
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

    /**
     * @return the ID of the {@code Follow} sent for it
     */
    private String sentFollow(Actor followed, String state) {
        var id = UUID.randomUUID();
        var activity = local.uri() + "#follows/" + id;
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, :state, :activity)
                        """)
                .param("id", id)
                .param("follower", local.id())
                .param("followed", followed.id())
                .param("state", state)
                .param("activity", activity)
                .update();
        return activity;
    }

    private long follows(Actor actor) {
        return count("SELECT count(*) FROM follow WHERE follower_id = :id OR followed_id = :id", actor.id());
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private List<String> activities(Actor actor, String idPattern) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id AND activity_id LIKE :pattern")
                .param("id", actor.id())
                .param("pattern", idPattern)
                .query(String.class)
                .list();
    }

    private long publications() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single();
    }

    private long jobs(Actor actor) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'account-cleanup' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + actor.id() + "%")
                .query(Long.class)
                .single();
    }

    private void runRemainingJob(Actor actor) {
        jdbc.sql("""
                        UPDATE scheduled_tasks SET execution_time = now()
                        WHERE task_name = 'account-cleanup' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + actor.id() + "%")
                .update();
    }
}
