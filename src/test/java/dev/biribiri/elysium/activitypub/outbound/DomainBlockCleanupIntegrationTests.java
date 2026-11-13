package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.moderation.DomainBlock;
import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.NewStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.Map;

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
    @Autowired
    private LikeService likes;
    @Autowired
    private PollService polls;
    @Autowired
    private BoostService boosts;

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
    void removesLikesOfActorsWhoHaveNoFollowsOrRemotePosts() {
        var liker = remote(domain);
        var status = statuses.create(local.id(), new NewStatus("post", null, null, Visibility.PUBLIC, false, null));
        likes.receive(liker.id(), status.id(), liker.uri() + "/like", Instant.now());
        assertThat(likes.count(status.id())).isOne();
        blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM status_like WHERE actor_id = :id")
                .param("id", liker.id()).query(Long.class).single() == 0);
        assertThat(likes.count(status.id())).isZero();
    }

    @Test
    void removesVotesOfItsActors() {
        var voter = remote(domain);
        var status = statuses.create(local.id(), new NewStatus("Which?", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, null, new NewPoll(List.of("Tea", "Coffee"), 600, false, false)));
        polls.receiveVote(status.id(), voter.id(), URI.create(voter.uri() + "#votes/1"), "Tea");
        blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM poll_vote WHERE voter_id = :id")
                .param("id", voter.id()).query(Long.class).single() == 0);
    }

    @Test
    void removesBoostsByItsActorsAndLocalBoostsOfTheirStatuses() {
        var booster = remote(domain);
        var status = statuses.create(local.id(), new NewStatus("post", null, null, Visibility.PUBLIC, false, null));
        boosts.receive(booster.id(), status.id(), URI.create(booster.uri() + "/announce"), Visibility.PUBLIC, Instant.now(),
                Instant.now());
        var remoteStatus = statuses.storeRemote(new RemoteStatus(URI.create(booster.uri() + "/posts/1"), null,
                booster.id(), "<p>remote</p>", null, null, Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        boosts.reblog(remoteStatus, local.id(), Visibility.PUBLIC).orElseThrow();
        assertThat(jdbc.sql("SELECT count(*) FROM status WHERE reblog_of_id IS NOT NULL AND (reblog_of_id = :status "
                + "OR reblog_of_id = :remote)").param("status", status.id()).param("remote", remoteStatus)
                .query(Long.class).single()).isEqualTo(2);
        blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM status WHERE reblog_of_id IS NOT NULL "
                        + "AND (reblog_of_id = :status OR reblog_of_id = :remote)")
                .param("status", status.id()).param("remote", remoteStatus).query(Long.class).single() == 0);
    }

    @Test
    void removesItsActorsBlocksAndKeepsLocalAccountsBlocksOfThem() {
        var blocker = remote(domain);
        var blocked = remote(domain);
        block(blocker, local);
        block(local, blocked);
        follow(blocker, local);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> follows(blocker) == 0 && blockCount("account_id", blocker) == 0);
        assertThat(blockCount("target_id", blocked)).isOne();
    }

    private void block(Actor account, Actor target) {
        jdbc.sql("""
                        INSERT INTO account_block (id, account_id, target_id, activity_uri)
                        VALUES (gen_random_uuid(), :account, :target, :activity)
                        """)
                .param("account", account.id())
                .param("target", target.id())
                .param("activity", "https://example.test/activities/" + UUID.randomUUID())
                .update();
    }

    private long blockCount(String column, Actor actor) {
        return jdbc.sql("SELECT count(*) FROM account_block WHERE " + column + " = :id").param("id", actor.id())
                .query(Long.class).single();
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
    void forgetsWhichFollowersOnlyStatusesItsActorsWereSent() {
        var follower = remote(domain);
        var other = remote("not" + domain);
        var status = UUID.randomUUID();
        for (var recipient : List.of(follower, other)) {
            jdbc.sql("""
                            INSERT INTO followers_only_recipient (status_id, author_id, actor_id)
                            VALUES (:status, :author, :actor)
                            """)
                    .param("status", status)
                    .param("author", local.id())
                    .param("actor", recipient.id())
                    .update();
        }
        follow(follower, local);

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> follows(follower) == 0);
        assertThat(jdbc.sql("SELECT actor_id FROM followers_only_recipient WHERE status_id = :status")
                .param("status", status).query(UUID.class).list()).containsExactly(other.id());
    }

    @Test
    void forgetsWhoALocalFollowersOnlyBoostOfItsActorsStatusWasSentTo() {
        var author = remote(domain);
        var recipient = remote("not" + domain);
        var remoteStatus = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/posts/1"), null,
                author.id(), "<p>remote</p>", null, null, Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        var boost = boosts.reblog(remoteStatus, local.id(), Visibility.PRIVATE).orElseThrow();
        jdbc.sql("""
                        INSERT INTO followers_only_recipient (status_id, author_id, actor_id)
                        VALUES (:status, :author, :actor)
                        """)
                .param("status", boost.id())
                .param("author", local.id())
                .param("actor", recipient.id())
                .update();

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> !statusExists(remoteStatus));
        assertThat(jdbc.sql("SELECT count(*) FROM followers_only_recipient WHERE status_id = :status")
                .param("status", boost.id()).query(Long.class).single()).isZero();
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

    @Test
    void aBlockThatOnlyLimitsAServerRemovesNothingUntilItSuspendsIt() {
        var follower = remote(domain);
        follow(follower, local);
        var status = post(follower);
        var block = blocks.create(domain, DomainBlock.Severity.NOOP, true, null);

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'domain-block-cleanup' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + block.id() + "%").query(Long.class).single()).isZero();
        assertThat(follows(follower)).isEqualTo(1);
        assertThat(statusExists(status)).isTrue();

        blocks.update(block.id(), DomainBlock.Severity.SUSPEND, false, null);

        await().atMost(WAIT).until(() -> follows(follower) == 0 && !statusExists(status));
    }

    @Test
    void removesTheAttachmentsOfItsStatuses() {
        var author = remote(domain);
        follow(local, author);
        statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/notes/" + UUID.randomUUID()), null, author.id(),
                "<p>Hi</p>", null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(new RemoteAttachment(URI.create(author.uri() + "/a.png"), AttachmentType.IMAGE, "image/png",
                        null, null, null, null, null)))).orElseThrow();
        assertThat(attachmentsOf(author)).isOne();

        blocks.create(domain, null);

        await().atMost(WAIT).until(() -> attachmentsOf(author) == 0);
    }

    private long attachmentsOf(Actor author) {
        return jdbc.sql("SELECT count(*) FROM media_attachment WHERE account_id = :id").param("id", author.id())
                .query(Long.class).single();
    }

    @Test
    void cleansInboxDataForADomainWithNoFollows() {
        var blocked = remote("social." + domain + ":8443");
        var id = statuses.storeRemote(new RemoteStatus(URI.create(blocked.uri() + "/notes/1"), null, blocked.id(),
                "<p>direct</p>", null, null, Visibility.DIRECT, false, null, Instant.now(), null,
                Map.of(local.id(), false))).orElseThrow();
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM inbox_notification WHERE status_id = :id")
                .param("id", id).query(Long.class).single() == 1);
        blocks.create(domain, null);
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM inbox_conversation WHERE owner_id = :id")
                .param("id", local.id()).query(Long.class).single() == 0);
        assertThat(statusExists(id)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE sender_id = :id")
                .param("id", blocked.id()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM status_thread_alias WHERE actor_id = :id")
                .param("id", blocked.id()).query(Long.class).single()).isZero();
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
