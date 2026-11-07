package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.ProfileUpdated;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.media.MediaProperties;
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
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What followers' servers are told about a profile, and what's removed with an account. This looks at what's queued, rather
 * than what's received.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"db-scheduler.polling-interval=PT0.1S", "db-scheduler.heartbeat-interval=PT1S"})
class ProfileDeliveryIntegrationTests {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private MediaProperties media;

    private Actor local;

    @BeforeEach
    void setUp() {
        local = newLocal();
    }

    @Test
    void tellsFollowersAboutAChange() {
        follow(remote(), local);

        profiles.update(local.id(), ProfileChange.NONE
                .withDisplayName("Changed")
                .withFields(List.of(new ProfileChange.NewField("Site", "https://site.test"))));

        await().atMost(WAIT).until(() -> !activities(local, "%#profile/%").isEmpty());
        var update = json.readTree(activities(local, "%#profile/%").getFirst());
        assertThat(update.get("type").asString()).isEqualTo("Update");
        assertThat(update.get("@context").get(2).get("PropertyValue").asString()).isEqualTo("schema:PropertyValue");
        var person = update.get("object");
        assertThat(person.get("name").asString()).isEqualTo("Changed");
        assertThat(person.get("attachment").get(0).get("name").asString()).isEqualTo("Site");
        assertThat(person.has("updated")).isTrue();
    }

    @Test
    void sendsNothingWhenNothingChanged() {
        follow(remote(), local);
        profiles.update(local.id(), ProfileChange.NONE.withDisplayName("Same"));
        await().atMost(WAIT).until(() -> activities(local, "%#profile/%").size() == 1);

        profiles.update(local.id(), ProfileChange.NONE.withDisplayName("Same").withAvatar(ProfileChange.Image.REMOVE));

        await().atMost(WAIT).until(() -> publications() == 0);
        assertThat(activities(local, "%#profile/%")).hasSize(1);
    }

    @Test
    void sendsNothingForASuspendedAccount() {
        follow(remote(), local);
        actors.suspend(local.id());
        await().atMost(WAIT).until(() -> !activities(local, "%#suspended/%").isEmpty());

        transactions.executeWithoutResult(status -> events.publishEvent(new ProfileUpdated(local.id(), Instant.now())));

        await().atMost(WAIT).until(() -> publications() == 0);
        assertThat(activities(local, "%#profile/%")).isEmpty();
        assertThat(profiles.update(local.id(), ProfileChange.NONE.withDisplayName("x"))).isFalse();
    }

    @Test
    void deletingAnAccountRemovesItsProfileAndImages() throws Exception {
        profiles.update(local.id(), ProfileChange.NONE
                .withDisplayName("Gone")
                .withFields(List.of(new ProfileChange.NewField("a", "b")))
                .withAvatar(ProfileChange.Image.upload(TestImages.animatedGif(2, 10, 10))));
        var avatar = profiles.find(local.id()).avatar();
        var file = media.directory().resolve(avatar.path().substring("/media/".length()));
        var still = media.directory().resolve(avatar.staticPath().substring("/media/".length()));
        assertThat(file).exists();
        assertThat(still).exists().isNotEqualTo(file);

        actors.markDeleted(local.id());

        await().atMost(WAIT).until(() -> Files.notExists(file) && Files.notExists(still));
        assertThat(jdbc.sql("SELECT count(*) FROM profile_field WHERE actor_id = :id").param("id", local.id())
                .query(Long.class).single()).isZero();
        assertThat(profiles.find(local.id()).displayName()).isEmpty();
    }

    private Actor newLocal() {
        var name = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
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
}
