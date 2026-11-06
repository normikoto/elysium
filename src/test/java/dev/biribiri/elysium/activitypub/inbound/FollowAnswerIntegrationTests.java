package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the handlers directly, with no worker running.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://answers.example", "elysium.jobs.worker-enabled=false"})
class FollowAnswerIntegrationTests {

    @Autowired
    private AcceptHandler acceptHandler;
    @Autowired
    private RejectHandler rejectHandler;
    @Autowired
    private FollowService follows;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;

    private Actor local;
    private Actor remote;

    @BeforeEach
    void setUp() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        local = actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow();
        remote = remote();
    }

    @Test
    void anAcceptNamingTheFollowMakesItActive() {
        follows.follow(local, remote);
        var followId = followId();

        acceptHandler.handle(answer("Accept", remote, "\"" + followId + "\""));

        assertThat(state()).containsExactly("accepted");
    }

    @Test
    void anAcceptEmbeddingTheFollowMakesItActive() {
        follows.follow(local, remote);

        acceptHandler.handle(answer("Accept", remote, embedded(followId(), local, remote)));

        assertThat(state()).containsExactly("accepted");
    }

    @Test
    void anAcceptEmbeddingTheFollowWithoutItsIdGoesByTheActors() {
        follows.follow(local, remote);

        acceptHandler.handle(answer("Accept", remote, embedded(null, local, remote)));

        assertThat(state()).containsExactly("accepted");
    }

    @Test
    void anAcceptFromAnotherActorDoesNothing() {
        follows.follow(local, remote);
        var other = remote();

        acceptHandler.handle(answer("Accept", other, "\"" + followId() + "\""));
        acceptHandler.handle(answer("Accept", other, embedded(null, local, remote)));
        acceptHandler.handle(answer("Accept", other, embedded(null, local, other)));

        assertThat(state()).containsExactly("pending");
    }

    @Test
    void anAcceptOfAnEarlierRequestDoesntAcceptANewOne() {
        follows.follow(local, remote);
        var earlier = followId();
        follows.unfollow(local, remote.id());
        follows.follow(local, remote);

        acceptHandler.handle(answer("Accept", remote, "\"" + earlier + "\""));

        assertThat(state()).containsExactly("pending");
    }

    @Test
    void anAcceptOfSomethingElseDoesNothing() {
        follows.follow(local, remote);

        acceptHandler.handle(answer("Accept", remote,
                "{\"id\":\"" + followId() + "\",\"type\":\"QuoteRequest\",\"actor\":\"" + local.uri() + "\"}"));

        assertThat(state()).containsExactly("pending");
    }

    @Test
    void aRejectRemovesTheRequest() {
        follows.follow(local, remote);

        rejectHandler.handle(answer("Reject", remote, "\"" + followId() + "\""));

        assertThat(state()).isEmpty();
    }

    @Test
    void aRejectRemovesAnAcceptedFollow() {
        follows.follow(local, remote);
        acceptHandler.handle(answer("Accept", remote, "\"" + followId() + "\""));

        rejectHandler.handle(answer("Reject", remote, embedded(null, local, remote)));

        assertThat(state()).isEmpty();
    }

    @Test
    void aRejectFromAnotherActorDoesNothing() {
        follows.follow(local, remote);

        rejectHandler.handle(answer("Reject", remote(), "\"" + followId() + "\""));

        assertThat(state()).containsExactly("pending");
    }

    @Test
    void doesntTouchFollowsOfLocalAccounts() {
        var follower = remote();
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", local.id())
                .param("activity", follower.uri() + "#follow")
                .update();

        rejectHandler.handle(answer("Reject", follower, "\"" + follower.uri() + "#follow\""));

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", local.id())
                .query(Long.class).single()).isEqualTo(1);
    }

    private InboundActivity answer(String type, Actor from, String object) {
        var id = from.uri() + "#answers/" + UUID.randomUUID();
        var body = "{\"id\":\"%s\",\"type\":\"%s\",\"actor\":\"%s\",\"object\":%s}".formatted(id, type, from.uri(), object);
        return new InboundActivity(id, from.uri().toString(), type, body);
    }

    private static String embedded(String id, Actor follower, Actor followed) {
        return "{%s\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(
                id == null ? "" : "\"id\":\"" + id + "\",", follower.uri(), followed.uri());
    }

    private String followId() {
        return jdbc.sql("SELECT activity_uri FROM follow WHERE follower_id = :follower AND followed_id = :followed")
                .param("follower", local.id())
                .param("followed", remote.id())
                .query(String.class)
                .single();
    }

    private List<String> state() {
        return jdbc.sql("SELECT state FROM follow WHERE follower_id = :follower AND followed_id = :followed")
                .param("follower", local.id())
                .param("followed", remote.id())
                .query(String.class)
                .list();
    }

    private Actor remote() {
        var domain = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));
    }
}
