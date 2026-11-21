package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewPoll;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.StatusEdit;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Looks at what's queued rather than what's received, as {@code AccountCleanupIntegrationTests} does. Each test ends by
 * queuing something that does federate, so that an empty queue can't come from listeners that haven't run yet.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class LocalOnlyIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private BoostService boosts;
    @Autowired
    private LikeService likes;
    @Autowired
    private StatusPinService pins;
    @Autowired
    private JdbcClient jdbc;

    private Actor author;
    private Actor booster;
    private Actor follower;

    @BeforeEach
    void setUp() {
        author = newLocal();
        booster = newLocal();
        follower = remote();
        follow(follower, author);
        follow(follower, booster);
    }

    @Test
    void sendsNothingAboutALocalOnlyStatusOrItsBoostsPinsAndPoll() {
        var parent = remoteStatus(follower);
        var status = statuses.create(author.id(), localOnly("Only here", parent));
        var poll = statuses.create(author.id(), new NewStatus("Which?", null, null, Visibility.PUBLIC, false, null,
                List.of(), null, null, new NewPoll(List.of("Tea", "Coffee"), 600, false, false), true));
        statuses.edit(status.id(), author.id(), new StatusEdit("Still only here", null, null, null));
        pins.pin(status.id(), author.id());
        pins.unpin(status.id(), author.id());
        var boost = boosts.reblog(status.id(), booster.id(), Visibility.PUBLIC).orElseThrow();
        boosts.unreblog(status.id(), booster.id());
        statuses.delete(poll.id(), author.id());
        statuses.delete(status.id(), author.id());

        assertThat(boost.localOnly()).isTrue();
        var shown = statuses.create(author.id(), new NewStatus("Everyone", null, null, Visibility.PUBLIC, false, null));
        await().atMost(WAIT).until(() -> publications() == 0);
        assertThat(activities(author)).containsExactly(shown.uri() + "#create");
        assertThat(activities(booster)).isEmpty();
    }

    @Test
    void showsALocalOnlyStatusOnlyToAccountsHere() {
        var status = statuses.create(author.id(), localOnly("Only here", null));
        var followersOnly = statuses.create(author.id(), new NewStatus("Followers", null, null, Visibility.PRIVATE,
                false, null, List.of(), null, null, null, true));

        assertThat(statuses.findVisible(status.id(), Optional.of(booster.id()))).isPresent();
        assertThat(statuses.findVisible(status.id(), Optional.empty())).isEmpty();
        assertThat(statuses.findVisible(status.id(), Optional.of(follower.id()))).isEmpty();
        assertThat(statuses.findVisible(followersOnly.id(), Optional.of(follower.id()))).isEmpty();
        assertThat(statuses.findFetchable(status.id(), Optional.of(follower.id()))).isEmpty();
        assertThat(statuses.findVisibleToAnyone(List.of(status.id()))).isEmpty();
        assertThat(statuses.findVisible(List.of(status.id()), follower.id())).isEmpty();
        assertThat(statuses.findVisible(List.of(status.id()), booster.id())).hasSize(1);
        assertThat(statuses.countVisibleToAnyone(author.id())).isZero();
    }

    @Test
    void refusesBoostsAndLikesFromOtherServers() {
        var status = statuses.create(author.id(), localOnly("Only here", null));
        var now = Instant.now();

        boosts.receive(follower.id(), status.id(), URI.create(follower.uri() + "/announces/1"), Visibility.PUBLIC, now,
                now);
        likes.receive(follower.id(), status.id(), follower.uri() + "/likes/1", now);

        assertThat(boosts.countPublic(status.id())).isZero();
        assertThat(likes.count(status.id())).isZero();
    }

    private static NewStatus localOnly(String text, UUID inReplyToId) {
        return new NewStatus(text, null, null, Visibility.PUBLIC, false, inReplyToId, List.of(), null, null, null, true);
    }

    private UUID remoteStatus(Actor remote) {
        return remoteStatuses.storeRemote(new RemoteStatus(
                URI.create(remote.uri() + "/notes/" + UUID.randomUUID()), null, remote.id(), "<p>Hi</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
    }

    private Actor newLocal() {
        var name = "lo" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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

    private List<String> activities(Actor actor) {
        return jdbc.sql("SELECT activity_id FROM outbound_activity WHERE actor_id = :id")
                .param("id", actor.id())
                .query(String.class)
                .list();
    }

    private long publications() {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single();
    }
}
