package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Calls the handlers and checks directly with no worker running, as {@link CreateIntegrationTests} does. Other servers are a
 * mock, and real ones are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://delete.example", "elysium.jobs.worker-enabled=false"})
class DeleteIntegrationTests {

    private static final String PUBLIC = "https://www.w3.org/ns/activitystreams#Public";

    @Autowired
    private DeleteHandler handler;
    @Autowired
    private CreateHandler creates;
    @Autowired
    private DeletionChecks checks;
    @Autowired
    private ForwardedActivities forwarded;
    @Autowired
    private Inbox inbox;
    @Autowired
    private RemoteActors remoteActors;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor follower;
    private Actor author;
    private Actor neighbour;
    private String host;

    @BeforeEach
    void setUp() {
        follower = actors.createLocalIfAbsent("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20), actor -> {
        }).orElseThrow();
        host = "h" + UUID.randomUUID() + ".test";
        author = remote("s");
        neighbour = remote("t");
        follow(follower, author);
        follow(follower, neighbour);
    }

    @Test
    void deletesAStatusSentAsAnId() {
        var status = stored(author);

        handler.handle(delete(author, "\"" + status + "\""));

        assertThat(exists(status)).isFalse();
    }

    @Test
    void deletesAStatusSentAsATombstone() {
        var status = stored(author);

        handler.handle(delete(author, json.writeValueAsString(Map.of("id", status, "type", "Tombstone"))));

        assertThat(exists(status)).isFalse();
    }

    @Test
    void cannotDeleteAnotherActorsStatus() {
        var status = stored(author);

        handler.handle(delete(neighbour, "\"" + status + "\""));

        assertThat(exists(status)).isTrue();
    }

    @Test
    void ignoresAnObjectOnAnotherServer() {
        var other = remoteOn("o" + UUID.randomUUID() + ".test");
        follow(follower, other);
        var status = stored(other);

        handler.handle(delete(author, "\"" + status + "\""));

        assertThat(exists(status)).isTrue();
    }

    @Test
    void aDeleteThatRunsBeforeItsCreateKeepsTheStatusFromBeingStored() {
        var status = statusId();

        handler.handle(delete(author, "\"" + status + "\""));
        creates.handle(create(author, status));

        assertThat(exists(status)).isFalse();
    }

    @Test
    void aDeleteFromAnotherActorDoesNotStopTheCreate() {
        var status = statusId();

        handler.handle(delete(neighbour, "\"" + status + "\""));
        creates.handle(create(author, status));

        assertThat(exists(status)).isTrue();
    }

    @Test
    void aRepeatIsHarmless() {
        var status = stored(author);
        var delete = delete(author, "\"" + status + "\"");

        handler.handle(delete);
        handler.handle(delete);

        assertThat(exists(status)).isFalse();
    }

    @Test
    void anActorDeletingItselfIsGoneForGood() {
        handler.handle(delete(author, "\"" + author.uri() + "\""));

        var stored = actors.findById(author.id()).orElseThrow();
        assertThat(stored.isDeleted()).isTrue();
        assertThat(actors.isInactive(author.uri())).isTrue();
        assertThatThrownBy(() -> remoteActors.fetchOrRefresh(author.uri())).isInstanceOf(PermanentJobFailure.class);
        var signer = new VerifiedSignature(author.uri() + "#main-key", author.uri());
        var later = "{\"id\":\"%s/follows/1\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}"
                .formatted(author.uri(), author.uri(), follower.uri());
        assertThat(inbox.receive(signer, later.getBytes(StandardCharsets.UTF_8)).outcome())
                .isEqualTo(Inbox.Outcome.FORBIDDEN);
    }

    @Test
    void anActorThatIsntStoredIsLeftAlone() {
        var uri = URI.create("https://" + host + "/users/" + UUID.randomUUID());

        handler.handle(delete(uri, "\"" + uri + "\""));

        assertThat(actors.findByUri(uri)).isEmpty();
        assertThat(markers()).isZero();
    }

    // Checking with the object's server

    @Test
    void aCheckDeletesWhatItsServerSaysIsGone() throws Exception {
        var gone = stored(author);
        var tombstone = stored(author);
        when(documents.fetch(URI.create(gone))).thenThrow(DocumentFetchException.gone("gone"));
        when(documents.fetch(URI.create(tombstone)))
                .thenReturn(json.readTree(json.writeValueAsString(Map.of("id", tombstone, "type", "Tombstone"))));

        checks.confirm(URI.create(gone), author.uri());
        checks.confirm(URI.create(tombstone), author.uri());

        assertThat(exists(gone)).isFalse();
        assertThat(exists(tombstone)).isFalse();
    }

    @Test
    void aCheckKeepsWhatItsServerStillServes() throws Exception {
        var status = stored(author);
        when(documents.fetch(URI.create(status))).thenReturn(json.readTree(json.writeValueAsString(note(status))));

        checks.confirm(URI.create(status), author.uri());

        assertThat(exists(status)).isTrue();
    }

    @Test
    void aCheckIsRetriedWhenTheServerCantBeReached() throws Exception {
        var status = stored(author);
        when(documents.fetch(URI.create(status))).thenThrow(DocumentFetchException.temporary("down", null));

        assertThatThrownBy(() -> checks.confirm(URI.create(status), author.uri())).isInstanceOf(IllegalStateException.class);
        assertThat(exists(status)).isTrue();
    }

    @Test
    void aCheckDeletesAnActorWhoseServerSaysItsGone() throws Exception {
        when(documents.fetch(author.uri())).thenThrow(DocumentFetchException.gone("gone"));

        checks.confirm(author.uri(), author.uri());

        assertThat(actors.findById(author.id()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void aForwardedDeleteThatItsOriginDoesNotServeIsCheckedWithTheObjectsServer() throws Exception {
        var status = stored(author);
        var id = status + "#delete";
        var forwarder = URI.create("https://f" + UUID.randomUUID() + ".test/users/f");
        var signer = new VerifiedSignature(forwarder + "#main-key", forwarder);

        var result = inbox.receive(signer, bytes(delete(author, "\"" + status + "\"", id)));

        assertThat(result.outcome()).isEqualTo(Inbox.Outcome.FORWARDED);
        var job = jdbc.sql("SELECT convert_from(task_data, 'UTF8') FROM scheduled_tasks WHERE task_name = 'forwarded-activity' "
                        + "AND convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + status + "#delete%")
                .query(String.class)
                .single();
        assertThat(json.readTree(job).get("deletedObject").asString()).isEqualTo(status);

        when(documents.fetch(URI.create(id))).thenThrow(DocumentFetchException.gone("gone"));
        when(documents.fetch(URI.create(status))).thenThrow(DocumentFetchException.gone("gone"));
        forwarded.fetch(new ForwardedJob(id, Instant.now(), status, author.uri().toString()));

        assertThat(exists(status)).isFalse();
    }

    // Deletes whose key is gone

    @Test
    void anActorsOwnDeleteWithAGoneKeyQueuesACheck() {
        var result = inbox.receiveWithGoneKey(author.uri(), bytes(delete(author, "\"" + author.uri() + "\"")));

        assertThat(result).map(Inbox.Result::outcome).contains(Inbox.Outcome.ACCEPTED);
        assertThat(checkJobs(author.uri())).isOne();
        assertThat(actors.findById(author.id()).orElseThrow().isDeleted()).isFalse();
    }

    @Test
    void aGoneKeyIsRefusedForAnythingElse() {
        var status = stored(author);
        var unknown = URI.create("https://" + host + "/users/" + UUID.randomUUID());

        assertThat(inbox.receiveWithGoneKey(author.uri(), bytes(delete(author, "\"" + status + "\"")))).isEmpty();
        assertThat(inbox.receiveWithGoneKey(neighbour.uri(), bytes(delete(author, "\"" + author.uri() + "\"")))).isEmpty();
        assertThat(inbox.receiveWithGoneKey(unknown, bytes(delete(unknown, "\"" + unknown + "\"")))).isEmpty();
        assertThat(inbox.receiveWithGoneKey(author.uri(), bytes(create(author, status)))).isEmpty();
        assertThat(checkJobs(author.uri())).isZero();
        assertThat(checkJobs(unknown)).isZero();
    }

    private Actor remote(String name) {
        return remoteOn(host, name);
    }

    private Actor remoteOn(String domain) {
        return remoteOn(domain, "s");
    }

    private Actor remoteOn(String domain, String name) {
        var uri = URI.create("https://" + domain + "/users/" + name);
        return actors.upsertRemote(new RemoteActorProfile(
                uri, name, domain, URI.create(uri + "/inbox"), null, null, null, URI.create(uri + "/followers")));
    }

    private String statusId() {
        return "https://" + host + "/notes/" + UUID.randomUUID();
    }

    /**
     * @return the URI of a new status by this actor, stored through its {@code Create}
     */
    private String stored(Actor actor) {
        var id = "https://" + actor.uri().getHost() + "/notes/" + UUID.randomUUID();
        creates.handle(create(actor, id));
        assertThat(exists(id)).isTrue();
        return id;
    }

    private Map<String, Object> note(String id) {
        return Map.of("id", id, "type", "Note", "attributedTo", author.uri().toString(), "to", List.of(PUBLIC), "content", "Hi");
    }

    private InboundActivity create(Actor actor, String noteId) {
        var note = Map.of(
                "id", noteId,
                "type", "Note",
                "attributedTo", actor.uri().toString(),
                "to", List.of(PUBLIC),
                "cc", List.of(actor.uri() + "/followers"),
                "content", "<p>Hi</p>");
        var id = noteId + "/activity";
        var body = "{\"id\":\"%s\",\"type\":\"Create\",\"actor\":\"%s\",\"object\":%s}"
                .formatted(id, actor.uri(), json.writeValueAsString(note));
        return new InboundActivity(id, actor.uri().toString(), "Create", body);
    }

    private InboundActivity delete(Actor actor, String object) {
        return delete(actor.uri(), object);
    }

    private InboundActivity delete(URI actor, String object) {
        return delete(actor, object, actor + "#deletes/" + UUID.randomUUID());
    }

    private InboundActivity delete(Actor actor, String object, String id) {
        return delete(actor.uri(), object, id);
    }

    private InboundActivity delete(URI actor, String object, String id) {
        var body = "{\"id\":\"%s\",\"type\":\"Delete\",\"actor\":\"%s\",\"object\":%s}".formatted(id, actor, object);
        return new InboundActivity(id, actor.toString(), "Delete", body);
    }

    private static byte[] bytes(InboundActivity activity) {
        return activity.body().getBytes(StandardCharsets.UTF_8);
    }

    private boolean exists(String uri) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM status WHERE uri = :uri)").param("uri", uri).query(Boolean.class).single();
    }

    private long markers() {
        return jdbc.sql("SELECT count(*) FROM deleted_remote_status WHERE actor_uri LIKE :host")
                .param("host", "https://" + host + "/%")
                .query(Long.class)
                .single();
    }

    private long checkJobs(URI actor) {
        return jdbc.sql("""
                        SELECT count(*) FROM scheduled_tasks
                        WHERE task_name = 'deletion-check' AND convert_from(task_data, 'UTF8') LIKE :actor
                        """)
                .param("actor", "%" + actor + "%")
                .query(Long.class)
                .single();
    }

    private void follow(Actor from, Actor to) {
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (:id, :follower, :followed, 'accepted', :activity)
                        """)
                .param("id", UUID.randomUUID())
                .param("follower", from.id())
                .param("followed", to.id())
                .param("activity", from.uri() + "#follows/" + UUID.randomUUID())
                .update();
    }
}
