package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.content.AttachmentType;
import dev.biribiri.elysium.content.RemoteAttachment;
import dev.biribiri.elysium.content.RemoteStatus;
import java.util.Map;
import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.MediaUpload;
import dev.biribiri.elysium.media.MediaProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.LikeService;
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
    private LikeService likes;
    @Autowired
    private BoostService boosts;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private MediaAttachments attachments;
    @Autowired
    private MediaProperties media;

    private Actor local;

    @BeforeEach
    void setUp() {
        local = newLocal();
    }

    @Test
    void deletingAnAccountRemovesItsMediaFilesPostedOrNot() throws IOException {
        var posted = upload();
        var waiting = upload();
        statuses.create(local.id(), new NewStatus("With media", "", null, Visibility.PUBLIC, false, null,
                List.of(posted.id())));
        var files = Stream.of(posted.fileName(), posted.previewName(), waiting.fileName(), waiting.previewName())
                .map(media.directory()::resolve)
                .toList();
        assertThat(files).allMatch(Files::exists);

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> files.stream().noneMatch(Files::exists));
        assertThat(count("SELECT count(*) FROM media_attachment WHERE account_id = :id", local.id())).isZero();
    }

    private MediaAttachment upload() throws IOException {
        return attachments.upload(local.id(), new MediaUpload(
                new ByteArrayInputStream(TestImages.png(8, 8)), null, null, null));
    }

    @Test
    void deletingALikerRemovesItsLikesAndFavouriteNotifications() {
        var author = newLocal();
        var status = statuses.create(author.id(), draft());
        likes.favourite(status.id(), local.id());
        assertThat(likes.count(status.id())).isOne();
        actors.markDeleted(local.id());
        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM status_like WHERE actor_id = :id", local.id()) == 0);
        assertThat(likes.count(status.id())).isZero();
        assertThat(count("SELECT count(*) FROM inbox_notification WHERE sender_id = :id AND type = 'favourite'", local.id())).isZero();
    }

    @Test
    void deletingABoosterRemovesItsBoostsAndReblogNotifications() {
        var author = newLocal();
        var status = statuses.create(author.id(), draft());
        boosts.reblog(status.id(), local.id(), Visibility.PUBLIC).orElseThrow();
        await().atMost(WAIT).until(() ->
                count("SELECT count(*) FROM inbox_notification WHERE sender_id = :id AND type = 'reblog'", local.id()) == 1);
        actors.markDeleted(local.id());
        await().atMost(WAIT).until(() ->
                count("SELECT count(*) FROM status WHERE actor_id = :id AND reblog_of_id IS NOT NULL", local.id()) == 0);
        assertThat(count("SELECT count(*) FROM inbox_notification WHERE sender_id = :id AND type = 'reblog'", local.id()))
                .isZero();
    }

    @Test
    void deletingAnAccountRemovesItsBlocksAndMutesAndThoseOfIt() {
        var other = newLocal();
        var remote = remote();
        insert("account_block", local, other);
        insert("account_block", other, local);
        insert("account_block", remote, local);
        insert("account_mute", local, other);
        insert("account_mute", other, local);
        jdbc.sql("INSERT INTO account_domain_block (id, account_id, domain) VALUES (gen_random_uuid(), :id, 'example.com')")
                .param("id", local.id()).update();

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> count("""
                SELECT (SELECT count(*) FROM account_block WHERE account_id = :id OR target_id = :id)
                     + (SELECT count(*) FROM account_mute WHERE account_id = :id OR target_id = :id)
                     + (SELECT count(*) FROM account_domain_block WHERE account_id = :id)""", local.id()) == 0);
    }

    @Test
    void suspendingARemoteActorKeepsItsBlocks() {
        var suspended = remote();
        insert("account_block", suspended, local);
        insert("account_block", local, suspended);
        follow(suspended, newLocal());

        actors.suspend(suspended.id());

        await().atMost(WAIT).until(() -> follows(suspended) == 0);
        assertThat(count("SELECT count(*) FROM account_block WHERE account_id = :id OR target_id = :id", suspended.id()))
                .isEqualTo(2);
    }

    private void insert(String table, Actor account, Actor target) {
        var activity = "account_block".equals(table) ? ", activity_uri" : "";
        var value = "account_block".equals(table) ? ", 'https://example.test/activities/" + UUID.randomUUID() + "'" : "";
        jdbc.sql("INSERT INTO " + table + " (id, account_id, target_id" + activity + ") VALUES (gen_random_uuid(), :account, :target"
                        + value + ")")
                .param("account", account.id())
                .param("target", target.id())
                .update();
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
    void deletingAnAccountForgetsWhoItsFollowersOnlyStatusesWentToAndWhatItWasSent() {
        var other = newLocal();
        var deletedRemote = remote();
        var keptRemote = remote();
        var mine = UUID.randomUUID();
        var theirs = UUID.randomUUID();
        recordRecipient(mine, local, keptRemote);
        recordRecipient(theirs, other, deletedRemote);
        recordRecipient(theirs, other, keptRemote);

        actors.markDeleted(local.id());
        assertThat(actors.markRemoteDeleted(deletedRemote.id())).isTrue();

        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM followers_only_recipient WHERE status_id = :id", mine) == 0
                && count("SELECT count(*) FROM followers_only_recipient WHERE actor_id = :id", deletedRemote.id()) == 0);
        assertThat(count("SELECT count(*) FROM followers_only_recipient WHERE status_id = :id", theirs)).isOne();
    }

    @Test
    void deletingAnAuthorForgetsWhoLocalFollowersOnlyBoostsOfItsStatusesWereSentTo() {
        var booster = newLocal();
        var recipient = remote();
        var remoteAuthor = remote();
        var remoteStatus = statuses.storeRemote(new RemoteStatus(URI.create(remoteAuthor.uri() + "/statuses/1"), null,
                remoteAuthor.id(), "<p>Hi</p>", null, null, Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        var ofRemote = boosts.reblog(remoteStatus, booster.id(), Visibility.PRIVATE).orElseThrow();
        var localStatus = statuses.create(local.id(), draft());
        var ofLocal = boosts.reblog(localStatus.id(), booster.id(), Visibility.PRIVATE).orElseThrow();
        recordRecipient(ofRemote.id(), booster, recipient);
        recordRecipient(ofLocal.id(), booster, recipient);

        assertThat(actors.markRemoteDeleted(remoteAuthor.id())).isTrue();
        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() ->
                count("SELECT count(*) FROM followers_only_recipient WHERE status_id = :id", ofRemote.id()) == 0
                        && count("SELECT count(*) FROM followers_only_recipient WHERE status_id = :id", ofLocal.id()) == 0);
    }

    @Test
    void deletingARemoteActorRemovesItsDataAndSendsNothing() {
        var deleted = remote();
        follow(deleted, local);
        follow(local, deleted);
        jdbc.sql("""
                        INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at)
                        VALUES (:id, :uri, :actorId, '', '', 'public', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("uri", deleted.uri() + "/statuses/1")
                .param("actorId", deleted.id())
                .update();
        statuses.storeRemote(new RemoteStatus(URI.create(deleted.uri() + "/statuses/2"), null, deleted.id(), "<p>Hi</p>",
                null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(),
                List.of(new RemoteAttachment(URI.create(deleted.uri() + "/a.png"), AttachmentType.IMAGE, "image/png",
                        null, null, null, null, null)))).orElseThrow();

        assertThat(actors.markRemoteDeleted(deleted.id())).isTrue();

        await().atMost(WAIT).until(() -> follows(deleted) == 0
                && count("SELECT count(*) FROM status WHERE actor_id = :id", deleted.id()) == 0
                && count("SELECT count(*) FROM media_attachment WHERE account_id = :id", deleted.id()) == 0);
        assertThat(actors.findById(deleted.id()).orElseThrow().isDeleted()).isTrue();
        assertThat(actors.isInactive(deleted.uri())).isTrue();
        assertThat(activities(deleted, "%")).isEmpty();
        assertThat(activities(local, "%")).isEmpty();
        assertThat(actors.markRemoteDeleted(deleted.id())).isFalse();
        assertThat(actors.markRemoteDeleted(local.id())).isFalse();
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
                status.id(), status.uri(), local.id(), status.visibility(), status.publishedAt(), null, List.of(), List.of())));

        await().atMost(WAIT).until(() -> !activities(local, status.uri() + "#delete").isEmpty());
    }

    @Test
    void aStatusOfADeletedAuthorGetsNoDeleteOfItsOwn() {
        follow(remote(), local);
        var status = statuses.create(local.id(), draft());
        actors.markDeleted(local.id());

        transactions.executeWithoutResult(tx -> events.publishEvent(new StatusDeleted(
                status.id(), status.uri(), local.id(), status.visibility(), status.publishedAt(), null, List.of(), List.of())));

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

    @Test
    void deletingAnAccountRemovesItsInboxAndReferencesFromOtherParticipants() {
        var other = newLocal();
        var status = statuses.create(local.id(), new NewStatus("@" + other.username() + " direct", "", null,
                Visibility.DIRECT, false, null));
        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM inbox_conversation WHERE owner_id = :id", local.id()) == 1
                && count("SELECT count(*) FROM inbox_notification WHERE sender_id = :id", local.id()) == 1);
        jdbc.sql("INSERT INTO inbox_marker (owner_id, timeline, last_read_id) VALUES (:id, 'home', :status)")
                .param("id", local.id()).param("status", status.id()).update();
        actors.markDeleted(local.id());
        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM inbox_marker WHERE owner_id = :id", local.id()) == 0
                && count("SELECT count(*) FROM inbox_conversation WHERE owner_id = :id OR participant_ids @> ARRAY[CAST(:id AS uuid)]", local.id()) == 0);
        assertThat(count("SELECT count(*) FROM inbox_notification WHERE sender_id = :id OR recipient_id = :id", local.id())).isZero();
        assertThat(count("SELECT count(*) FROM status_thread_alias WHERE actor_id = :id", local.id())).isZero();
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

    private void recordRecipient(UUID statusId, Actor author, Actor recipient) {
        jdbc.sql("""
                        INSERT INTO followers_only_recipient (status_id, author_id, actor_id)
                        VALUES (:status, :author, :actor)
                        """)
                .param("status", statusId)
                .param("author", author.id())
                .param("actor", recipient.id())
                .update();
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
