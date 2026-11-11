package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * With {@code elysium.federation.send-blocks} off, the follows a block ends are still ended on the other server, but the
 * block itself isn't sent.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://unsent-blocks.example",
        "elysium.jobs.worker-enabled=false",
        "elysium.federation.send-blocks=false"
})
class BlocksNotSentIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private FollowService follows;
    @Autowired
    private BlockService blocks;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void endsTheFollowsWithoutSendingTheBlockOrItsUndo() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        var local = actors.findLocalByUsername(name).orElseThrow();
        var remote = remote();
        follows.follow(local, remote);
        await().atMost(WAIT).until(() -> types(local), found -> found.contains("Follow"));
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", remote.id())
                .param("followed", local.id())
                .param("activity", remote.uri() + "#follows/1")
                .update();

        blocks.block(local, remote);
        await().atMost(WAIT).until(() -> types(local), found -> found.containsAll(List.of("Undo", "Reject")));
        blocks.unblock(local, remote.id());

        // The listeners for the block and the unblock have had as long as those for the follows.
        await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> !types(local).contains("Block"));
        // The Follow was never sent, so the Undo cancelled it.
        assertThat(types(local)).containsExactlyInAnyOrder("Undo", "Reject");
    }

    private Actor remote() {
        var domain = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "dave", domain, URI.create(uri + "/inbox"), null, null, null,
                null));
    }

    private List<String> types(Actor actor) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id").param("id", actor.id())
                .query(String.class).list().stream()
                .map(body -> JsonPath.<String>read(body, "$.type"))
                .toList();
    }
}
