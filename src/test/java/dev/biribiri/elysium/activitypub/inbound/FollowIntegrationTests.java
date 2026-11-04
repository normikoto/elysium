package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calls the handlers directly with no worker running, so that each step can be seen and nothing is really sent. The handlers
 * being run from jobs is tested in {@link InboundActivitiesIntegrationTests}, and sending in
 * {@code ActivityDeliveryIntegrationTests}. Real servers are tested by the federation lab.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://follow.example", "elysium.jobs.worker-enabled=false"})
class FollowIntegrationTests {

    private static final String BASE = "https://follow.example";

    @Autowired
    private FollowHandler followHandler;
    @Autowired
    private UndoHandler undoHandler;
    @Autowired
    private InboundActivities inbound;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private ActivityDocuments documents;

    private Actor followed;
    private String host;
    private String followerUri;

    @BeforeEach
    void setUp() throws Exception {
        var name = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        followed = actors.findLocalByUsername(name).orElseThrow();
        host = "h" + UUID.randomUUID() + ".test";
        followerUri = "https://" + host + "/ap/users/1";
        serve(followerUri, "bob");
    }

    // Following

    @Test
    void storesTheActorAndTheFollowAndQueuesAnAccept() throws Exception {
        var followId = newId();

        followHandler.handle(follow(followId));

        var follower = actors.findByUri(URI.create(followerUri)).orElseThrow();
        assertThat(follower.username()).isEqualTo("bob");
        assertThat(follower.domain()).isEqualTo(host);
        assertThat(follower.inboxUri()).hasToString(followerUri + "/inbox");
        assertThat(actors.findLocalByUri(follower.uri())).isEmpty();
        assertThat(follows()).containsExactly(followId);
        assertThat(state()).isEqualTo("accepted");

        var bodies = accepts();
        assertThat(bodies).hasSize(1);
        var accept = json.readTree(bodies.getFirst());
        assertThat(accept.path("type").stringValue()).isEqualTo("Accept");
        assertThat(accept.path("actor").stringValue()).isEqualTo(followed.uri().toString());
        assertThat(accept.path("id").stringValue()).startsWith(followed.uri() + "#accepts/follows/").doesNotContain(followId);
        assertThat(accept.path("@context").stringValue()).isEqualTo("https://www.w3.org/ns/activitystreams");
        var inner = accept.path("object");
        assertThat(inner.path("id").stringValue()).isEqualTo(followId);
        assertThat(inner.path("type").stringValue()).isEqualTo("Follow");
        assertThat(inner.path("actor").stringValue()).isEqualTo(followerUri);
        assertThat(inner.path("object").stringValue()).isEqualTo(followed.uri().toString());
        assertThat(deliveryInboxes()).containsExactly(followerUri + "/inbox");
    }

    @Test
    void signsTheAcceptAsTheFollowedAccount() {
        followHandler.handle(follow(newId()));

        var actorId = jdbc.sql("SELECT actor_id FROM outbound_activity WHERE body LIKE :body")
                .param("body", "%" + followerUri + "%")
                .query(UUID.class)
                .single();
        assertThat(actorId).isEqualTo(followed.id());
    }

    @Test
    void readsAnEmbeddedObject() {
        var followId = newId();
        var body = "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":{\"id\":\"%s\",\"type\":\"Person\"}}"
                .formatted(followId, followerUri, followed.uri());

        followHandler.handle(new InboundActivity(followId, followerUri, "Follow", body));

        assertThat(follows()).containsExactly(followId);
    }

    @Test
    void usesTheSignerAsTheFollowerAndNotTheBody() {
        var followId = newId();
        var body = "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"https://other.test/users/victim\",\"object\":\"%s\"}"
                .formatted(followId, followed.uri());

        followHandler.handle(new InboundActivity(followId, followerUri, "Follow", body));

        assertThat(follows()).containsExactly(followId);
        assertThat(actors.findByUri(URI.create("https://other.test/users/victim"))).isEmpty();
    }

    @Test
    void runningTwiceLeavesOneFollowAndDoesNotFail() {
        var activity = follow(newId());

        followHandler.handle(activity);
        followHandler.handle(activity);

        assertThat(follows()).hasSize(1);
        assertThat(accepts()).hasSize(1);
        assertThat(deliveryInboxes()).hasSize(1);
    }

    @Test
    void aNewFollowGetsAnAcceptNamingIt() {
        var first = newId();
        var second = newId();

        followHandler.handle(follow(first));
        followHandler.handle(follow(second));

        assertThat(follows()).containsExactly(second);
        assertThat(accepts()).hasSize(2).anySatisfy(body -> assertThat(body).contains(second))
                .anySatisfy(body -> assertThat(body).contains(first));
    }

    @Test
    void ignoresAFollowOfSomeoneWhoIsNotLocal() throws Exception {
        for (var target : List.of(BASE + "/users/nobody", "https://other.test/users/alice", "not a uri")) {
            followHandler.handle(followOf(newId(), target));
        }

        verify(documents, never()).fetch(any());
        assertThat(followerRows()).isZero();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void ignoresAFollowOfTheInstanceActor() throws Exception {
        followHandler.handle(followOf(newId(), BASE + "/actor"));

        verify(documents, never()).fetch(any());
        assertThat(followerRows()).isZero();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void ignoresAFollowWithoutAnObject() {
        var id = newId();

        followHandler.handle(new InboundActivity(id, followerUri, "Follow", "{\"id\":\"" + id + "\"}"));

        assertThat(followerRows()).isZero();
    }

    // Remote actors

    @Test
    void doesNotFetchAnActorItHasARecentCopyOf() throws Exception {
        followHandler.handle(follow(newId()));
        followHandler.handle(follow(newId()));

        verify(documents, times(1)).fetch(URI.create(followerUri));
    }

    @Test
    void refreshesAnOldCopyOfAnActor() throws Exception {
        followHandler.handle(follow(newId()));
        jdbc.sql("UPDATE actor SET updated_at = now() - INTERVAL '25 hours' WHERE uri = :uri").param("uri", followerUri).update();
        serve(followerUri, "robert");

        followHandler.handle(follow(newId()));

        var follower = actors.findByUri(URI.create(followerUri)).orElseThrow();
        assertThat(follower.username()).isEqualTo("robert");
        assertThat(follower.updatedAt()).isAfter(java.time.Instant.now().minusSeconds(3600));
        assertThat(follows()).hasSize(1);
    }

    @Test
    void refusesADocumentAndStoresNothing() throws Exception {
        when(documents.fetch(eq(URI.create(followerUri)))).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"bob\",\"inbox\":\"https://other.test/inbox\"}"
                        .formatted(followerUri)));

        assertThatThrownBy(() -> followHandler.handle(follow(newId()))).isInstanceOf(PermanentJobFailure.class);

        assertThat(actors.findByUri(URI.create(followerUri))).isEmpty();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void refusesAnActorClaimingToBeOnThisServerWithoutFetchingIt() throws Exception {
        var claimed = BASE + "/users/" + followed.username();

        assertThatThrownBy(() -> followHandler.handle(new InboundActivity(newId(), claimed, "Follow",
                "{\"object\":\"%s\"}".formatted(followed.uri())))).isInstanceOf(PermanentJobFailure.class);

        verify(documents, never()).fetch(any());
    }

    @Test
    void retriesATemporaryFailureAndParksOtherFailures() throws Exception {
        var activity = follow(newId());
        when(documents.fetch(any())).thenThrow(DocumentFetchException.temporary("Down", null));
        assertThatThrownBy(() -> followHandler.handle(activity)).isNotInstanceOf(PermanentJobFailure.class);

        org.mockito.Mockito.reset(documents);
        when(documents.fetch(any())).thenThrow(DocumentFetchException.gone("Gone"));
        assertThatThrownBy(() -> followHandler.handle(activity)).isInstanceOf(PermanentJobFailure.class);

        org.mockito.Mockito.reset(documents);
        when(documents.fetch(any())).thenThrow(DocumentFetchException.permanent("Bad", null));
        assertThatThrownBy(() -> followHandler.handle(activity)).isInstanceOf(PermanentJobFailure.class);
    }

    /**
     * A remote server can give a username to a new actor after renaming the old one.
     */
    @Test
    void parksWhenAnotherActorOnTheServerHasTheUsername() throws Exception {
        followHandler.handle(follow(newId()));
        var renamed = "https://" + host + "/ap/users/2";
        serve(renamed, "BOB");

        assertThatThrownBy(() -> followHandler.handle(new InboundActivity(newId(), renamed, "Follow",
                "{\"object\":\"%s\"}".formatted(followed.uri()))))
                .isInstanceOf(PermanentJobFailure.class)
                .hasMessageContaining("username");
    }

    @Test
    void aRemoteActorAndALocalAccountCanHaveTheSameName() throws Exception {
        serve(followerUri, followed.username());

        followHandler.handle(follow(newId()));
        var name = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var other = "https://" + host + "/ap/users/3";
        serve(other, name);
        followHandler.handle(new InboundActivity(newId(), other, "Follow", "{\"object\":\"%s\"}".formatted(followed.uri())));
        var created = actors.createLocalIfAbsent(name, actor -> {
        });

        assertThat(follows()).hasSize(2);
        assertThat(actors.findLocalByUsername(followed.username())).isPresent();
        assertThat(created).isPresent();
    }

    @Test
    void remoteActorsAreNotCountedAsLocal() {
        var before = actors.countLocal();

        followHandler.handle(follow(newId()));

        assertThat(actors.countLocal()).isEqualTo(before);
        assertThat(actors.findLocalByUsername("bob").filter(actor -> actor.domain().equals(host))).isEmpty();
    }

    // Undoing

    @Test
    void undoRemovesTheFollow() {
        var followId = newId();
        followHandler.handle(follow(followId));

        undoHandler.handle(undo(followerUri, embeddedFollow(followId, followerUri)));

        assertThat(follows()).isEmpty();
    }

    @Test
    void undoCanNameTheFollowWithJustItsId() {
        var followId = newId();
        followHandler.handle(follow(followId));

        undoHandler.handle(undo(followerUri, "\"" + followId + "\""));

        assertThat(follows()).isEmpty();
    }

    @Test
    void anotherActorsUndoDoesNotRemoveTheFollow() throws Exception {
        var followId = newId();
        followHandler.handle(follow(followId));
        var other = "https://" + host + "/ap/users/2";
        serve(other, "carol");
        followHandler.handle(new InboundActivity(newId(), other, "Follow", "{\"object\":\"%s\"}".formatted(followed.uri())));

        undoHandler.handle(undo(other, "\"" + followId + "\""));
        undoHandler.handle(undo(other, embeddedFollow(followId, followerUri)));

        assertThat(follows()).contains(followId);
    }

    @Test
    void undoWithAnEmbeddedActorWhoIsNotTheSignerDoesNothing() {
        var followId = newId();
        followHandler.handle(follow(followId));

        undoHandler.handle(undo(followerUri, embeddedFollow(followId, "https://" + host + "/ap/users/2")));

        assertThat(follows()).containsExactly(followId);
        assertThat(undone(followId)).isFalse();
    }

    @Test
    void undoWithoutAnIdMatchesTheTwoActors() {
        followHandler.handle(follow(newId()));

        undoHandler.handle(undo(followerUri, "{\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}"
                .formatted(followerUri, followed.uri())));

        assertThat(follows()).isEmpty();
    }

    @Test
    void undoOfAnOlderFollowLeavesTheNewerOne() {
        var older = newId();
        var newer = newId();
        followHandler.handle(follow(older));
        followHandler.handle(follow(newer));

        undoHandler.handle(undo(followerUri, embeddedFollow(older, followerUri)));

        assertThat(follows()).containsExactly(newer);
    }

    @Test
    void ignoresAnUndoOfAnotherType() {
        var followId = newId();
        followHandler.handle(follow(followId));

        undoHandler.handle(undo(followerUri, "{\"id\":\"%s\",\"type\":\"Like\",\"actor\":\"%s\"}".formatted(followId, followerUri)));

        assertThat(follows()).containsExactly(followId);
        assertThat(undone(followId)).isFalse();
    }

    // Jobs have no order

    @Test
    void anUndoBeforeItsFollowLeavesNoFollow() throws Exception {
        var followId = newId();

        undoHandler.handle(undo(followerUri, embeddedFollow(followId, followerUri)));
        followHandler.handle(follow(followId));

        assertThat(followerRows()).isZero();
        assertThat(accepts()).isEmpty();
        verify(documents, never()).fetch(any());
    }

    @Test
    void aNewFollowAfterAnUndoCreatesAFollow() {
        var undone = newId();
        var next = newId();
        undoHandler.handle(undo(followerUri, embeddedFollow(undone, followerUri)));
        followHandler.handle(follow(undone));

        followHandler.handle(follow(next));

        assertThat(follows()).containsExactly(next);
    }

    @Test
    void aFollowRunningAgainAfterItsUndoDoesNotAddTheFollowerBack() {
        var activity = follow(newId());
        followHandler.handle(activity);
        undoHandler.handle(undo(followerUri, embeddedFollow(activity.id(), followerUri)));

        followHandler.handle(activity);

        assertThat(follows()).isEmpty();
    }

    /**
     * The fetch happens outside the transaction, so an Undo can run after the Follow has already looked for one.
     */
    @Test
    void anUndoDuringTheFetchLeavesNoFollow() throws Exception {
        var followId = newId();
        var document = json.readTree(actorDocument(followerUri, "bob"));
        when(documents.fetch(eq(URI.create(followerUri)))).thenAnswer(invocation -> {
            undoHandler.handle(undo(followerUri, embeddedFollow(followId, followerUri)));
            return document;
        });

        followHandler.handle(follow(followId));

        assertThat(follows()).isEmpty();
        assertThat(accepts()).isEmpty();
    }

    /**
     * Otherwise a server could stop another server's follow by undoing its ID before it arrives.
     */
    @Test
    void anUndoFromAnotherServerCannotStopAFollow() {
        var followId = newId();

        undoHandler.handle(undo("https://attacker.test/users/mallory", "\"" + followId + "\""));
        followHandler.handle(follow(followId));

        assertThat(undone(followId)).isFalse();
        assertThat(follows()).containsExactly(followId);
    }

    /**
     * Only the inbox's checks limit IDs, and the object of an Undo doesn't go through them. Too long an ID would fail the
     * insert every time the job is tried.
     */
    @Test
    void ignoresAnUndoWithAnIdTooLongToStore() {
        var followId = "https://" + host + "/" + "a".repeat(10_000);

        undoHandler.handle(undo(followerUri, "\"" + followId + "\""));

        assertThat(undone(followId)).isFalse();
    }

    @Test
    void ignoresAnUndoWithoutAnObject() {
        var id = newId();

        undoHandler.handle(new InboundActivity(id, followerUri, "Undo", "{\"id\":\"" + id + "\"}"));

        assertThat(undone(id)).isFalse();
    }

    // Servers that use the same IDs each time, as Misskey does

    @Test
    void aRepeatedFollowAfterAnUndoFollowsAgain() {
        var activity = follow(newId());
        var undo = undo(followerUri, embeddedFollow(activity.id(), followerUri));
        inbound.accept(activity);
        followHandler.handle(activity);
        inbound.accept(undo);
        undoHandler.handle(undo);
        assertThat(follows()).isEmpty();

        assertThat(inbound.accept(activity)).isFalse();
        followHandler.handle(activity);

        assertThat(undone(activity.id())).isFalse();
        assertThat(follows()).containsExactly(activity.id());
    }

    @Test
    void aRepeatedUndoUnfollowsAgain() {
        var activity = follow(newId());
        var undo = undo(followerUri, embeddedFollow(activity.id(), followerUri));
        inbound.accept(activity);
        followHandler.handle(activity);
        inbound.accept(undo);
        undoHandler.handle(undo);
        inbound.accept(activity);
        followHandler.handle(activity);
        removeJobs(undo);

        assertThat(inbound.accept(undo)).isFalse();

        assertThat(inboundJobs(undo)).isOne();
        undoHandler.handle(undo);
        assertThat(follows()).isEmpty();
    }

    @Test
    void aRepeatedFollowIsProcessedAgain() {
        var activity = follow(newId());
        inbound.accept(activity);
        followHandler.handle(activity);
        removeJobs(activity);

        inbound.accept(activity);

        assertThat(inboundJobs(activity)).isOne();
    }

    @Test
    void anotherActorRepeatingAFollowDoesNothing() {
        var activity = follow(newId());
        inbound.accept(activity);
        undoHandler.handle(undo(followerUri, embeddedFollow(activity.id(), followerUri)));
        removeJobs(activity);

        inbound.accept(new InboundActivity(activity.id(), "https://" + host + "/ap/users/2", "Follow", activity.body()));

        assertThat(undone(activity.id())).isTrue();
        assertThat(inboundJobs(activity)).isZero();
    }

    // Cleanup

    @Test
    void theFollowTableIsEmptiedWhenAnActorIsDeleted() {
        followHandler.handle(follow(newId()));

        jdbc.sql("DELETE FROM actor WHERE uri = :uri").param("uri", followerUri).update();

        assertThat(jdbc.sql("SELECT count(*) FROM follow WHERE followed_id = :id").param("id", followed.id())
                .query(Long.class).single()).isZero();
    }

    // Helpers

    private String newId() {
        return "https://" + host + "/activities/" + UUID.randomUUID();
    }

    private InboundActivity follow(String id) {
        return followOf(id, followed.uri().toString());
    }

    private InboundActivity followOf(String id, String target) {
        var body = "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(id, followerUri, target);
        return new InboundActivity(id, followerUri, "Follow", body);
    }

    private InboundActivity undo(String actor, String object) {
        var id = newId();
        var body = "{\"id\":\"%s\",\"type\":\"Undo\",\"actor\":\"%s\",\"object\":%s}".formatted(id, actor, object);
        return new InboundActivity(id, actor, "Undo", body);
    }

    private String embeddedFollow(String id, String actor) {
        return "{\"id\":\"%s\",\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(id, actor, followed.uri());
    }

    private void serve(String uri, String username) throws Exception {
        when(documents.fetch(eq(URI.create(uri)))).thenReturn(json.readTree(actorDocument(uri, username)));
    }

    private static String actorDocument(String uri, String username) {
        return "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"%s\",\"inbox\":\"%s/inbox\"}"
                .formatted(uri, username, uri);
    }

    private void removeJobs(InboundActivity activity) {
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'inbound-activity' AND convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .update();
    }

    private long inboundJobs(InboundActivity activity) {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'inbound-activity' AND convert_from(task_data, 'UTF8') LIKE :id")
                .param("id", "%" + activity.id() + "%")
                .query(Long.class)
                .single();
    }

    private List<String> follows() {
        return jdbc.sql("SELECT activity_uri FROM follow WHERE followed_id = :id").param("id", followed.id())
                .query(String.class).list();
    }

    private String state() {
        return jdbc.sql("SELECT state FROM follow WHERE followed_id = :id").param("id", followed.id())
                .query(String.class).single();
    }

    private long followerRows() {
        return jdbc.sql("SELECT count(*) FROM actor WHERE domain = :host").param("host", host).query(Long.class).single();
    }

    private boolean undone(String id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM undone_follow WHERE activity_uri = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    private List<String> accepts() {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id").param("id", followed.id())
                .query(String.class).list();
    }

    private List<String> deliveryInboxes() {
        return jdbc.sql("""
                        SELECT convert_from(task_data, 'UTF8')::json ->> 'inbox' FROM scheduled_tasks
                        WHERE task_name = 'activity-delivery' AND convert_from(task_data, 'UTF8') LIKE :id
                        """)
                .param("id", "%" + followed.uri() + "%")
                .query(String.class)
                .list();
    }
}
