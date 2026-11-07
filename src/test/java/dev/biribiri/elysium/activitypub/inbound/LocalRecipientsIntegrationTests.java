package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://recipients.example", "elysium.jobs.worker-enabled=false"})
class LocalRecipientsIntegrationTests {

    @Autowired
    private LocalRecipients recipients;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;

    private Actor alice;
    private Actor bob;
    private Actor sender;
    private String followers;

    @BeforeEach
    void setUp() {
        alice = local();
        bob = local();
        var domain = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/s");
        followers = uri + "/followers";
        sender = actors.upsertRemote(new RemoteActorProfile(
                uri, "s", domain, URI.create(uri + "/inbox"), null, null, null, URI.create(followers)));
    }

    @Test
    void findsLocalAccountsAddressedInAnyProperty() {
        var body = """
                {"type":"Create","to":"%s","object":{"cc":[{"id":"%s"}],"bcc":"https://elsewhere.test/users/x"}}"""
                .formatted(alice.uri(), bob.uri());

        assertThat(recipients.of(activity("Create", body))).containsExactlyInAnyOrder(alice.id(), bob.id());
    }

    @Test
    void doesNotFindAccountsAddressedOnlyInTheObjectOfAnAnnounce() {
        var body = "{\"type\":\"Announce\",\"to\":\"%s\",\"object\":{\"to\":\"%s\"}}".formatted(alice.uri(), bob.uri());

        assertThat(recipients.of(activity("Announce", body))).containsExactly(alice.id());
    }

    @Test
    void skipsAddressesWithNoSchemeOrHost() {
        var body = "{\"to\":[\"//recipients.example%s\",\"urn:x:y\",\"/users/x\",\"%s\"]}"
                .formatted(alice.uri().getPath(), bob.uri());

        assertThat(recipients.of(activity("Create", body))).containsExactly(bob.id());
    }

    @Test
    void findsAnAccountAddressedOnlyInBcc() {
        var body = "{\"to\":\"as:Public\",\"bcc\":[\"%s\"]}".formatted(alice.uri());

        assertThat(recipients.of(activity("Create", body))).containsExactly(alice.id());
    }

    @Test
    void findsTheSendersLocalFollowersWhenItsFollowersAreAddressed() {
        follow(alice, "accepted");
        follow(bob, "pending");
        var suspended = local();
        follow(suspended, "accepted");
        actors.suspend(suspended.id());

        var body = "{\"to\":\"Public\",\"cc\":\"%s\"}".formatted(followers);

        assertThat(recipients.of(activity("Create", body))).containsExactly(alice.id());
    }

    @Test
    void doesNotFindFollowersWhenTheirCollectionIsNotAddressed() {
        follow(alice, "accepted");

        var body = "{\"to\":\"https://www.w3.org/ns/activitystreams#Public\",\"cc\":\"%s/other\"}".formatted(sender.uri());

        assertThat(recipients.of(activity("Create", body))).isEmpty();
    }

    @Test
    void findsTheObjectOfAFollow() {
        assertThat(recipients.of(activity("Follow", "{\"object\":\"%s\"}".formatted(alice.uri()))))
                .containsExactly(alice.id());
    }

    @Test
    void leavesOutSuspendedAndUnknownAccounts() {
        actors.suspend(bob.id());

        var body = "{\"to\":[\"%s\",\"https://recipients.example/users/nobody\",\"not a uri\",\"%s\"]}"
                .formatted(bob.uri(), sender.uri());

        assertThat(recipients.of(activity("Create", body))).isEmpty();
    }

    private InboundActivity activity(String type, String body) {
        return new InboundActivity(sender.uri() + "/activities/" + UUID.randomUUID(), sender.uri().toString(), type, body);
    }

    private Actor local() {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow();
    }

    private void follow(Actor follower, String state) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, :state, :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", follower.id())
                .param("followed", sender.id())
                .param("state", state)
                .param("activity", follower.uri() + "#follows/" + UUID.randomUUID())
                .update();
    }
}
