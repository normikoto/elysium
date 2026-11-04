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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private StoredActivities stored;
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
        assertThat(follower.updatedAt()).isAfter(Instant.now().minusSeconds(3600));
        assertThat(follows()).hasSize(1);
    }

    @Test
    void usesAnOldCopyOfAnActorWhenItCannotBeRefreshed() throws Exception {
        followHandler.handle(follow(newId()));
        for (var failure : List.of(
                DocumentFetchException.temporary("Down", null),
                DocumentFetchException.permanent("A firewall's answer", null))) {
            jdbc.sql("UPDATE actor SET updated_at = now() - INTERVAL '25 hours' WHERE uri = :uri").param("uri", followerUri).update();
            when(documents.fetch(any())).thenThrow(failure);
            var followId = newId();

            followHandler.handle(follow(followId));

            assertThat(follows()).containsExactly(followId);
            org.mockito.Mockito.reset(documents);
        }
    }

    @Test
    void refusesAFollowFromAnActorWhoIsGoneEvenWithAnOldCopy() throws Exception {
        followHandler.handle(follow(newId()));
        jdbc.sql("UPDATE actor SET updated_at = now() - INTERVAL '25 hours' WHERE uri = :uri").param("uri", followerUri).update();
        when(documents.fetch(any())).thenThrow(DocumentFetchException.gone("Gone"));

        assertThatThrownBy(() -> followHandler.handle(follow(newId()))).isInstanceOf(PermanentJobFailure.class);
    }

    @Test
    void refusesANewDocumentThatCannotBeUsedEvenWithAnOldCopy() throws Exception {
        followHandler.handle(follow(newId()));
        jdbc.sql("UPDATE actor SET updated_at = now() - INTERVAL '25 hours' WHERE uri = :uri").param("uri", followerUri).update();
        when(documents.fetch(eq(URI.create(followerUri)))).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"type\":\"Person\",\"preferredUsername\":\"bob\",\"inbox\":\"https://other.test/inbox\"}"
                        .formatted(followerUri)));

        assertThatThrownBy(() -> followHandler.handle(follow(newId()))).isInstanceOf(PermanentJobFailure.class);
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
     * A remote server can give a username to a new actor after renaming the old one, and this server's copy of the old one
     * only changes when it's refreshed.
     */
    @Test
    void anotherActorOnTheServerCanHaveTheSameUsername() throws Exception {
        followHandler.handle(follow(newId()));
        var other = "https://" + host + "/ap/users/2";
        serve(other, "BOB");

        followHandler.handle(new InboundActivity(newId(), other, "Follow", "{\"object\":\"%s\"}".formatted(followed.uri())));

        assertThat(follows()).hasSize(2);
        assertThat(actors.findByUri(URI.create(other)).orElseThrow().username()).isEqualTo("BOB");
    }

    @Test
    void localUsernamesAreStillUniqueIgnoringCase() {
        var created = actors.createLocalIfAbsent(followed.username().toUpperCase(), actor -> {
        });

        assertThat(created).isEmpty();
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
        var older = arrive(follow(newId()));
        var newer = arrive(follow(newId()));
        followHandler.handle(older);
        followHandler.handle(newer);

        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(older.id(), followerUri))));

        assertThat(follows()).containsExactly(newer.id());
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

    // The order that activities arrived in

    @Test
    void aFollowThatArrivedEarlierDoesNotReplaceANewerOne() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var older = arrivedAt(follow(newId()), now.minusSeconds(60));
        var newer = arrivedAt(follow(newId()), now);

        followHandler.handle(newer);
        followHandler.handle(older);

        assertThat(follows()).containsExactly(newer.id());

        undoHandler.handle(undo(followerUri, embeddedFollow(newer.id(), followerUri)));

        assertThat(follows()).isEmpty();
    }

    // An Undo with no ID for its Follow

    @Test
    void anUndoWithNoIdThatRunsBeforeItsFollowLeavesNoFollow() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var activity = arrivedAt(follow(newId()), now.minusSeconds(60));

        undoHandler.handle(arrivedAt(undoOfPair(), now));
        followHandler.handle(activity);

        assertThat(follows()).isEmpty();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void aFollowRunningAgainAfterAnUndoWithNoIdDoesNotAddTheFollowerBack() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var activity = arrivedAt(follow(newId()), now.minusSeconds(60));
        followHandler.handle(activity);
        undoHandler.handle(arrivedAt(undoOfPair(), now));

        followHandler.handle(activity);

        assertThat(follows()).isEmpty();
    }

    @Test
    void aFollowThatArrivesAfterAnUndoWithNoIdFollowsAgain() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        undoHandler.handle(arrivedAt(undoOfPair(), now.minusSeconds(60)));
        var activity = arrivedAt(follow(newId()), now);

        followHandler.handle(activity);

        assertThat(follows()).containsExactly(activity.id());
    }

    @Test
    void anUndoWithNoIdRunningAgainDoesNotRemoveALaterFollow() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var undo = arrivedAt(undoOfPair(), now.minusSeconds(60));
        undoHandler.handle(undo);
        var activity = arrivedAt(follow(newId()), now);
        followHandler.handle(activity);

        undoHandler.handle(undo);

        assertThat(follows()).containsExactly(activity.id());
    }

    // An Undo and the Accept

    @Test
    void anUndoCancelsTheAcceptOfTheFollow() {
        var followId = newId();
        followHandler.handle(follow(followId));
        assertThat(accepts()).hasSize(1);

        undoHandler.handle(undo(followerUri, embeddedFollow(followId, followerUri)));

        assertThat(accepts()).isEmpty();
    }

    @Test
    void anUndoWithNoIdCancelsTheAcceptOfTheFollow() {
        followHandler.handle(follow(newId()));

        undoHandler.handle(undoOfPair());

        assertThat(accepts()).isEmpty();
    }

    // One actor can't affect another's

    @Test
    void anotherActorOnTheSameServerCannotStopAFollow() {
        var followId = newId();

        undoHandler.handle(undo("https://" + host + "/ap/users/2", "\"" + followId + "\""));
        followHandler.handle(follow(followId));

        assertThat(follows()).containsExactly(followId);
    }

    @Test
    void aNewFollowGetsAnAcceptNamingItAndReplacesTheOldOne() {
        var first = newId();
        var second = newId();

        followHandler.handle(follow(first));
        followHandler.handle(follow(second));

        assertThat(follows()).containsExactly(second);
        assertThat(accepts()).singleElement().satisfies(body -> assertThat(body).contains(second));
    }

    @Test
    void aFollowThatArrivedBeforeTheOneStoredGetsNoAccept() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var older = arrivedAt(follow(newId()), now.minusSeconds(60));
        var newer = arrivedAt(follow(newId()), now);
        followHandler.handle(newer);

        followHandler.handle(older);

        assertThat(accepts()).singleElement().satisfies(body -> assertThat(body).contains(newer.id()));
    }

    @Test
    void anUndoOfAnOlderFollowLeavesTheAcceptOfTheNewerOne() {
        var older = arrive(follow(newId()));
        var newer = arrive(follow(newId()));
        followHandler.handle(older);
        followHandler.handle(newer);

        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(older.id(), followerUri))));

        assertThat(follows()).containsExactly(newer.id());
        assertThat(accepts()).singleElement().satisfies(body -> assertThat(body).contains(newer.id()));
    }

    @Test
    void anUndoAlsoCoversFollowsThatArrivedBeforeTheOneItNames() {
        var older = arrive(follow(newId()));
        var newer = arrive(follow(newId()));
        var undo = arrive(undo(followerUri, embeddedFollow(newer.id(), followerUri)));

        followHandler.handle(older);
        undoHandler.handle(undo);
        followHandler.handle(newer);
        followHandler.handle(older);

        assertThat(follows()).isEmpty();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void anUndoWhoseFollowArrivedAfterItRemovesTheFollowWhicheverRunsFirst() {
        var followId = newId();
        var undo = arrive(undo(followerUri, embeddedFollow(followId, followerUri)));
        var follow = arrive(follow(followId));

        followHandler.handle(follow);
        assertThat(follows()).containsExactly(followId);
        undoHandler.handle(undo);
        assertThat(follows()).isEmpty();

        followHandler.handle(follow);
        assertThat(follows()).isEmpty();
    }

    @Test
    void anUndoNamingAFollowByIdWhoseJobHasNotRunStillCoversIt() {
        var follow = arrive(follow(newId()));

        undoHandler.handle(arrive(undo(followerUri, "\"" + follow.id() + "\"")));
        followHandler.handle(follow);

        assertThat(follows()).isEmpty();
    }

    @Test
    void doesNotFetchTheActorOfAFollowThatIsCovered() throws Exception {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var activity = arrivedAt(follow(newId()), now.minusSeconds(60));
        undoHandler.handle(arrivedAt(undoOfPair(), now));

        followHandler.handle(activity);

        verify(documents, never()).fetch(any());
    }

    @Test
    void aRepeatedUndoKeepsItsMarkerForLonger() {
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var followId = newId();
        var undo = undo(followerUri, "\"" + followId + "\"");
        undoHandler.handle(arrivedAt(undo, now.minus(java.time.Duration.ofDays(33))));

        undoHandler.handle(arrivedAt(undo, now));

        assertThat(jdbc.sql("SELECT undone_at FROM undone_follow WHERE activity_uri = :id").param("id", followId)
                .query(java.time.OffsetDateTime.class).single().toInstant()).isEqualTo(now);
    }

    /**
     * A server can make a new ID, and nothing else here knows the one that it names.
     */
    @Test
    void anUndoNamingAnUnknownIdStillRemovesTheFollowItDescribes() {
        followHandler.handle(arrive(follow(newId())));

        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(newId(), followerUri))));

        assertThat(follows()).isEmpty();
        assertThat(accepts()).isEmpty();
    }

    @Test
    void anUndoNamingJustAnUnknownIdLeavesTheFollow() {
        var followId = newId();
        followHandler.handle(arrive(follow(followId)));

        undoHandler.handle(arrive(undo(followerUri, "\"" + newId() + "\"")));

        assertThat(follows()).containsExactly(followId);
    }

    // Servers that use the same IDs each time, as Misskey does

    @Test
    void aRepeatedFollowAfterAnUndoFollowsAgain() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(activity.id(), followerUri))));
        assertThat(follows()).isEmpty();

        assertThat(inbound.accept(again(activity))).isFalse();
        followHandler.handle(stored(activity));

        assertThat(follows()).containsExactly(activity.id());
        assertThat(accepts()).hasSize(1);
    }

    /**
     * The answer to the first one may have failed and been set aside, and the same job wouldn't replace it.
     */
    @Test
    void aRepeatedFollowQueuesAnotherAnswer() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        assertThat(deliveryInboxes()).hasSize(1);

        inbound.accept(again(activity));
        followHandler.handle(stored(activity));

        assertThat(deliveryInboxes()).hasSize(2);
        assertThat(accepts()).hasSize(1);
    }

    @Test
    void aRepeatGetsItsOwnJobWhileTheLastOneIsStillQueued() {
        var activity = follow(newId());
        inbound.accept(activity);

        inbound.accept(again(activity));

        assertThat(inboundJobs(activity)).isEqualTo(2);
    }

    /**
     * The Undo arrived before the repeat, but its job ran after the repeat had arrived.
     */
    @Test
    void anUndoJobRunningLateDoesNotStopARepeatThatArrivedAfterIt() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        var undo = arrive(undo(followerUri, embeddedFollow(activity.id(), followerUri)));
        inbound.accept(again(activity));

        undoHandler.handle(undo);
        followHandler.handle(stored(activity));

        assertThat(follows()).containsExactly(activity.id());
        assertThat(accepts()).hasSize(1);
    }

    @Test
    void anUndoJobRunningAfterARepeatWasStoredLeavesItInPlace() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        var undo = arrive(undo(followerUri, embeddedFollow(activity.id(), followerUri)));
        inbound.accept(again(activity));
        followHandler.handle(stored(activity));

        undoHandler.handle(undo);

        assertThat(follows()).containsExactly(activity.id());
    }

    @Test
    void aFollowRunningAgainAfterARepeatedIdWasUndoneDoesNotAddTheFollowerBack() {
        var activity = follow(newId());
        var first = arrive(activity);
        followHandler.handle(first);
        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(activity.id(), followerUri))));

        followHandler.handle(first);

        assertThat(follows()).isEmpty();
    }

    @Test
    void aRepeatedUndoUnfollowsAgain() {
        var activity = follow(newId());
        var undo = undo(followerUri, embeddedFollow(activity.id(), followerUri));
        followHandler.handle(arrive(activity));
        undoHandler.handle(arrive(undo));
        inbound.accept(again(activity));
        followHandler.handle(stored(activity));
        removeJobs(undo);

        assertThat(inbound.accept(again(undo))).isFalse();

        assertThat(inboundJobs(undo)).isOne();
        undoHandler.handle(stored(undo));
        assertThat(follows()).isEmpty();
    }

    /**
     * Stored activities are cleaned up after about 34 days, and following someone usually lasts longer.
     */
    @Test
    void aFollowAfterAnUndoOfAFollowFromLongAgoFollowsAgain() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        removeStored(activity);
        undoHandler.handle(arrive(undo(followerUri, embeddedFollow(activity.id(), followerUri))));
        assertThat(follows()).isEmpty();

        followHandler.handle(arrive(again(activity)));

        assertThat(follows()).containsExactly(activity.id());
    }

    @Test
    void aRepeatStandsEvenIfAnUndoGotHereBeforeTheFirstFollow() {
        var activity = follow(newId());
        undoHandler.handle(undo(followerUri, embeddedFollow(activity.id(), followerUri)));
        inbound.accept(activity);
        inbound.accept(again(activity));

        followHandler.handle(stored(activity));

        assertThat(follows()).containsExactly(activity.id());
    }

    @Test
    void aRepeatedFollowIsProcessedAgain() {
        var activity = follow(newId());
        followHandler.handle(arrive(activity));
        removeJobs(activity);

        inbound.accept(again(activity));

        assertThat(inboundJobs(activity)).isOne();
    }

    @Test
    void anotherActorRepeatingAFollowDoesNothing() {
        var activity = follow(newId());
        var first = arrive(activity);
        removeJobs(activity);

        inbound.accept(new InboundActivity(activity.id(), "https://" + host + "/ap/users/2", "Follow", activity.body()));

        assertThat(stored(activity)).isEqualTo(first);
        assertThat(inboundJobs(activity)).isZero();
    }

    @Test
    void anotherActorUndoingAStoredFollowByItsIdDoesNotCoverIt() {
        var activity = arrive(follow(newId()));
        followHandler.handle(activity);

        undoHandler.handle(arrive(undo("https://" + host + "/ap/users/2", "\"" + activity.id() + "\"")));

        assertThat(follows()).containsExactly(activity.id());
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

    /**
     * Stores an activity the way an inbox does, and returns it the way its job would read it.
     */
    private InboundActivity arrive(InboundActivity activity) {
        inbound.accept(activity);
        return stored(activity);
    }

    /**
     * The same activity arriving now, which is what an inbox would make of a repeat.
     */
    private static InboundActivity again(InboundActivity activity) {
        return new InboundActivity(activity.id(), activity.actorUri(), activity.type(), activity.body());
    }

    private InboundActivity stored(InboundActivity activity) {
        return stored.find(activity.id()).orElseThrow();
    }

    private void removeStored(InboundActivity activity) {
        jdbc.sql("DELETE FROM inbound_activity WHERE activity_id = :id").param("id", activity.id()).update();
    }

    private InboundActivity undoOfPair() {
        return undo(followerUri, "{\"type\":\"Follow\",\"actor\":\"%s\",\"object\":\"%s\"}".formatted(followerUri, followed.uri()));
    }

    private static InboundActivity arrivedAt(InboundActivity activity, Instant time) {
        return new InboundActivity(activity.id(), activity.actorUri(), activity.type(), activity.body(), time);
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
