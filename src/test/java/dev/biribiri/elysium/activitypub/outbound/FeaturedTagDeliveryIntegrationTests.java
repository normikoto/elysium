package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestEvents;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.FeaturedTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What followers' servers are sent when a local account features a hashtag or stops. This looks at what's queued, rather
 * than what's received.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://elysium.example", "elysium.jobs.worker-enabled=false"})
class FeaturedTagDeliveryIntegrationTests {
    private static Instant started;

    @Autowired ActorService actors;
    @Autowired FeaturedTags featuredTags;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    private Actor alice;

    @BeforeAll
    static void rememberTheStart() {
        started = Instant.now();
    }

    @BeforeEach
    void setUp() {
        var name = "fta" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        alice = actors.createLocalIfAbsent(name, actor -> {}).orElseThrow();
    }

    @Test
    void sendsAnAddOfTheHashtagToFollowersAndARemoveAfterIt() {
        follow(remote(), alice);

        var tag = featuredTags.feature(alice.id(), "café");
        settled();

        var add = json.readTree(single("%#featured_tags/%"));
        assertThat(add.get("type").asString()).isEqualTo("Add");
        assertThat(add.get("@context").get(1).get("Hashtag").asString()).isEqualTo("as:Hashtag");
        assertThat(add.get("actor").asString()).isEqualTo(alice.uri().toString());
        assertThat(add.get("target").asString()).isEqualTo(alice.uri() + "/collections/featured");
        assertThat(add.get("object").get("type").asString()).isEqualTo("Hashtag");
        assertThat(add.get("object").get("name").asString()).isEqualTo("#café");
        assertThat(add.get("object").get("href").asString()).startsWith("https://elysium.example/tags/");
        assertThat(add.get("to").get(0).asString()).isEqualTo("https://www.w3.org/ns/activitystreams#Public");
        var addId = add.get("id").asString();

        featuredTags.unfeature(alice.id(), tag.id());
        settled();

        var remove = json.readTree(single("%#featured_tags/%/remove"));
        assertThat(remove.get("type").asString()).isEqualTo("Remove");
        assertThat(remove.get("id").asString()).isEqualTo(addId + "/remove");
        assertThat(remove.get("object").get("name").asString()).isEqualTo("#café");
        // Not yet sent, so it's withdrawn rather than sent before its Remove.
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id").param("id", addId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void sendsNothingWithoutRemoteFollowersOrForATagFeaturedAlready() {
        featuredTags.feature(alice.id(), "alone");
        settled();
        assertThat(count()).isZero();

        follow(remote(), alice);
        featuredTags.feature(alice.id(), "alone");
        settled();
        assertThat(count()).isZero();
    }

    private void follow(Actor follower, Actor followed) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :follower, :followed, 'accepted', :activity, now())
                        """)
                .param("id", UUID.randomUUID()).param("follower", follower.id()).param("followed", followed.id())
                .param("activity", follower.uri() + "#follows/" + UUID.randomUUID()).update();
    }

    private Actor remote() {
        var host = "fta" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/remote");
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", host, URI.create(uri + "/inbox"), null, null,
                null));
    }

    private String single(String idPattern) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :actor AND activity_id LIKE :pattern")
                .param("actor", alice.id()).param("pattern", idPattern).query(String.class).single();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE actor_id = :actor")
                .param("actor", alice.id()).query(Long.class).single();
    }

    private void settled() {
        TestEvents.awaitHandled(jdbc, started);
    }
}
