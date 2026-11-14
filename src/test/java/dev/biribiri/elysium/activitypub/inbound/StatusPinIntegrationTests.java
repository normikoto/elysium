package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPinService;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static dev.biribiri.elysium.content.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://pins.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class StatusPinIntegrationTests {
    private static final String AP = "application/activity+json";
    private static final Set<String> SCOPES = Set.of("read:statuses", "write:accounts");

    @Autowired MockMvc mvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired StatusPinService pins;
    @Autowired BoostService boosts;
    @Autowired FollowService follows;
    @Autowired AddHandler adds;
    @Autowired RemoveHandler removes;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM removed_pin").update();
        jdbc.sql("DELETE FROM outbound_activity").update();
        alice = local("pinalice");
        bob = local("pinbob");
    }

    @Test
    void pinsAreIdempotentAndOnlyTheirAuthorSeesThemAsPinned() throws Exception {
        var post = createPost(PUBLIC);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(api(post) + "/pin").with(user(alice)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(true));
        }
        mvc.perform(get(api(post)).with(user(alice))).andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(get(api(post)).with(user(bob))).andExpect(jsonPath("$.pinned").value(false));
        mvc.perform(get(api(post))).andExpect(jsonPath("$.pinned").value(false));
        mvc.perform(get("/api/v1/accounts/" + alice.id() + "/statuses").with(user(alice)))
                .andExpect(jsonPath("$[0].pinned").value(true));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(api(post) + "/unpin").with(user(alice)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.pinned").value(false));
        }
        mvc.perform(get(api(post)).with(user(alice))).andExpect(jsonPath("$.pinned").value(false));
    }

    @Test
    void refusesWhatMastodonRefuses() throws Exception {
        var direct = createPost(DIRECT);
        mvc.perform(post(api(direct) + "/pin").with(user(alice))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("only visible to mentioned users")));
        var others = statuses.create(bob.id(), new NewStatus("bob's", null, null, PUBLIC, false, null));
        mvc.perform(post(api(others) + "/pin").with(user(alice))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("Someone else's post")));
        var boost = boosts.reblog(others.id(), alice.id(), PUBLIC).orElseThrow();
        mvc.perform(post(api(boost) + "/pin").with(user(alice))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("A boost cannot be pinned")));
        for (int i = 0; i < StatusPinService.MAX_PER_ACCOUNT; i++) {
            mvc.perform(post(api(createPost(i % 2 == 0 ? PUBLIC : PRIVATE)) + "/pin").with(user(alice)))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(api(createPost(PUBLIC)) + "/pin").with(user(alice))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("maximum number")));
    }

    @Test
    void requiresTheAccountsScopeAndAUserAndDoesNotRevealHiddenStatuses() throws Exception {
        var post = statuses.create(bob.id(), new NewStatus("private", null, null, PRIVATE, false, null));
        for (var action : List.of("pin", "unpin")) {
            mvc.perform(post(api(post) + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(post(api(post) + "/" + action).with(scoped(alice.id(), Set.of("write:statuses"))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(api(post) + "/" + action).with(scoped(null, Set.of("write:accounts"))))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(post(api(post) + "/" + action).with(user(alice))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/statuses/not-an-id/" + action).with(user(alice))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/statuses/" + UUID.randomUUID() + "/" + action).with(user(alice)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void listsPinsTheMostRecentlyPinnedFirstToThoseWhoCanSeeThem() throws Exception {
        var older = createPost(PUBLIC);
        var hidden = createPost(PRIVATE);
        var newer = createPost(UNLISTED);
        pins.pin(newer.id(), alice.id());
        pins.pin(hidden.id(), alice.id());
        pins.pin(older.id(), alice.id());
        var path = "/api/v1/accounts/" + alice.id() + "/statuses";
        mvc.perform(get(path).param("pinned", "true"))
                .andExpect(jsonPath("$[*].id", contains(older.id().toString(), newer.id().toString())))
                .andExpect(header().doesNotExist("Link"));
        mvc.perform(get(path).param("pinned", "true").with(user(alice)))
                .andExpect(jsonPath("$[*].id", contains(older.id().toString(), hidden.id().toString(),
                        newer.id().toString())))
                .andExpect(jsonPath("$[*].pinned", everyItem(is(true))));
        mvc.perform(get(path).param("pinned", "true").param("limit", "1"))
                .andExpect(jsonPath("$[*].id", contains(older.id().toString())));
        mvc.perform(get(path).param("pinned", "true").param("max_id", older.id().toString()))
                .andExpect(jsonPath("$", empty()));
        follows.follow(bob, alice);
        mvc.perform(get(path).param("pinned", "true").with(user(bob)))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].pinned", everyItem(is(false))));
    }

    @Test
    void servesTheFeaturedCollectionAndAdvertisesIt() throws Exception {
        var shown = createPost(PUBLIC);
        pins.pin(createPost(PRIVATE).id(), alice.id());
        pins.pin(shown.id(), alice.id());
        mvc.perform(get("/users/pinalice").accept(AP))
                .andExpect(jsonPath("$.featured").value("https://pins.example/users/pinalice/collections/featured"))
                .andExpect(jsonPath("$['@context'][2].featured['@id']").value("toot:featured"))
                .andExpect(jsonPath("$['@context'][2].featured['@type']").value("@id"));
        mvc.perform(get("/users/pinalice/collections/featured").accept(AP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("https://pins.example/users/pinalice/collections/featured"))
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.orderedItems[0].type").value("Note"))
                .andExpect(jsonPath("$.orderedItems[0].id").value(shown.uri().toString()))
                .andExpect(jsonPath("$.orderedItems[0]['@context']").doesNotExist());
        mvc.perform(get("/users/nobody/collections/featured").accept(AP)).andExpect(status().isNotFound());
        actors.suspend(bob.id());
        try {
            mvc.perform(get("/users/pinbob/collections/featured").accept(AP)).andExpect(status().isForbidden());
            mvc.perform(get("/users/pinbob").accept(AP)).andExpect(jsonPath("$.featured").doesNotExist());
        } finally {
            actors.unsuspend(bob.id());
        }
    }

    @Test
    void sendsAnAddToFollowersAndARemoveAfterIt() throws Exception {
        var remote = remote();
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri, activity_at)
                        VALUES (:id, :follower, :followed, 'accepted', :activity, now())
                        """)
                .param("id", UUID.randomUUID()).param("follower", remote.id()).param("followed", alice.id())
                .param("activity", remote.uri() + "#follows/" + UUID.randomUUID()).update();
        var post = createPost(UNLISTED);
        pins.pin(post.id(), alice.id());
        settled();
        var add = json.readTree(single("%#pins/%"));
        assertThat(add.get("type").asString()).isEqualTo("Add");
        assertThat(add.get("actor").asString()).isEqualTo(alice.uri().toString());
        assertThat(add.get("object").asString()).isEqualTo(post.uri().toString());
        assertThat(add.get("target").asString()).isEqualTo(alice.uri() + "/collections/featured");
        assertThat(add.get("to").get(0).asString()).isEqualTo(alice.uri() + "/followers");
        var addId = add.get("id").asString();
        pins.unpin(post.id(), alice.id());
        settled();
        var remove = json.readTree(single("%#pins/%/remove"));
        assertThat(remove.get("type").asString()).isEqualTo("Remove");
        assertThat(remove.get("id").asString()).isEqualTo(addId + "/remove");
        assertThat(remove.get("object").asString()).isEqualTo(post.uri().toString());
        // Not yet sent, so it's withdrawn rather than sent before its Remove.
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id").param("id", addId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void sendsNothingWithoutRemoteFollowers() {
        pins.pin(createPost(PUBLIC).id(), alice.id());
        settled();
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id LIKE '%#pins/%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void receivesPinsOnlyForTheActorsOwnStatusesInItsFeaturedCollection() throws Exception {
        var remote = remote();
        var post = remotePost(remote);
        var someoneElse = remote();
        var theirs = remotePost(someoneElse);
        adds.handle(change(remote, "Add", theirs.uri(), featured(remote), ago(30)));
        adds.handle(change(remote, "Add", post.uri(), URI.create(remote.uri() + "/collections/other"), ago(30)));
        adds.handle(change(remote, "Add", Map.of("type", "Hashtag", "href", post.uri(), "name", "#tag"),
                featured(remote), ago(30)));
        assertThat(pinned(remote)).isEmpty();
        adds.handle(change(remote, "Add", post.uri(), featured(remote), ago(30)));
        assertThat(pinned(remote)).containsExactly(post.id());
        mvc.perform(get("/api/v1/accounts/" + remote.id() + "/statuses").param("pinned", "true"))
                .andExpect(jsonPath("$[*].id", contains(post.id().toString())));
        removes.handle(change(remote, "Remove", post.uri(), featured(remote), ago(20)));
        assertThat(pinned(remote)).isEmpty();
    }

    @Test
    void anAddThatArrivedBeforeItsRemoveDoesNotPinWhenItRunsAfterIt() {
        var remote = remote();
        var post = remotePost(remote);
        removes.handle(change(remote, "Remove", post.uri(), featured(remote), ago(10)));
        adds.handle(change(remote, "Add", post.uri(), featured(remote), ago(20)));
        assertThat(pinned(remote)).isEmpty();
        // Pinned again: the same body, which arrives later.
        adds.handle(change(remote, "Add", post.uri(), featured(remote), ago(1)));
        assertThat(pinned(remote)).containsExactly(post.id());
    }

    @Test
    void anAddOfAStatusThatIsNotStoredQueuesAFetch() {
        var remote = remote();
        var unknown = URI.create(remote.uri() + "/posts/" + UUID.randomUUID());
        adds.handle(change(remote, "Add", unknown, featured(remote), ago(1)));
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'featured-fetch'")
                .query(Long.class).single()).isPositive();
        var elsewhere = URI.create("https://elsewhere.test/posts/" + UUID.randomUUID());
        jdbc.sql("DELETE FROM scheduled_tasks WHERE task_name = 'featured-fetch'").update();
        adds.handle(change(remote, "Add", elsewhere, featured(remote), ago(1)));
        assertThat(jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_name = 'featured-fetch'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void replacingRemotePinsKeepsTheCollectionsOrderAndLaterRemoves() {
        var remote = remote();
        var first = remotePost(remote);
        var second = remotePost(remote);
        var removed = remotePost(remote);
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        pins.receiveUnpin(remote.id(), removed.uri(), now.plusSeconds(1));
        var listed = new LinkedHashMap<UUID, Instant>();
        listed.put(second.id(), now.minus(1, ChronoUnit.MICROS));
        listed.put(first.id(), now);
        listed.put(removed.id(), now.minus(2, ChronoUnit.MICROS));
        pins.replaceRemote(remote.id(), listed, now);
        assertThat(pins.findPinned(remote.id(), Optional.empty())).extracting(Status::id)
                .containsExactly(first.id(), second.id());
        pins.replaceRemote(remote.id(), Map.of(second.id(), now), now.plusSeconds(1));
        assertThat(pinned(remote)).containsExactly(second.id());
        pins.removeForDomain(remote.domain());
        assertThat(pinned(remote)).isEmpty();
    }

    @Test
    void aFetchedCollectionKeepsAPinThatArrivedWhileItWasBeingFetched() {
        var remote = remote();
        var post = remotePost(remote);
        var fetchedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        pins.receivePin(remote.id(), post, fetchedAt.plusSeconds(1));
        pins.replaceRemote(remote.id(), Map.of(), fetchedAt);
        assertThat(pinned(remote)).containsExactly(post.id());
    }

    private List<UUID> pinned(Actor actor) {
        return pins.findPinned(actor.id(), Optional.empty()).stream().map(Status::id).toList();
    }

    private InboundActivity change(Actor actor, String type, Object object, URI target, Instant at) {
        var body = json.writeValueAsString(Map.of("type", type, "actor", actor.uri(), "object", object, "target", target));
        return new InboundActivity(actor.uri() + "#" + type.toLowerCase() + "/test", actor.uri().toString(), type, body,
                at, at);
    }

    private static URI featured(Actor actor) {
        return URI.create(actor.uri() + "/collections/featured");
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote() {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/remote");
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", host, URI.create(uri + "/inbox"), null, null,
                null, null, featured(uri)));
    }

    private static URI featured(URI actor) {
        return URI.create(actor + "/collections/featured");
    }

    private Status createPost(Visibility visibility) {
        return statuses.create(alice.id(), new NewStatus("a post", null, null, visibility, false, null));
    }

    private Status remotePost(Actor author) {
        var id = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/posts/" + UUID.randomUUID()), null,
                author.id(), "<p>remote</p>", null, null, PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findVisible(id, Optional.empty()).orElseThrow();
    }

    private String single(String idPattern) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :actor AND activity_id LIKE :pattern")
                .param("actor", alice.id()).param("pattern", idPattern).query(String.class).single();
    }

    private Instant ago(long seconds) {
        return Instant.now().minusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private String api(Status status) {
        return "/api/v1/statuses/" + status.id();
    }

    private RequestPostProcessor user(Actor actor) {
        return scoped(actor.id(), SCOPES);
    }

    private RequestPostProcessor scoped(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600)));
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }
}
