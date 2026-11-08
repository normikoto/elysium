package dev.biribiri.elysium.activitypub.inbound;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.outbound.BoostDeliveries;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusReblogged;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.inbox.InboxProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static dev.biribiri.elysium.content.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://boosts.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class BoostIntegrationTests {
    private static final String AP = "application/activity+json";
    private static final String PUBLIC_COLLECTION = "https://www.w3.org/ns/activitystreams#Public";
    private static final Set<String> SCOPES = Set.of(
            "read:statuses", "write:statuses", "write:favourites", "read:notifications", "write:notifications");
    @Autowired MockMvc mvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired BoostService boosts;
    @Autowired AnnounceHandler handler;
    @Autowired UndoHandler undos;
    @Autowired DeleteHandler deletes;
    @Autowired FollowService follows;
    @Autowired BoostDeliveries deliveries;
    @Autowired InboxProjection projection;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM domain_block").update();
        jdbc.sql("DELETE FROM deleted_remote_status").update();
        alice = local("boostalice");
        bob = local("boostbob");
    }

    @Test
    void reblogsAreIdempotentAndShowAcrossTheApi() throws Exception {
        var post = createPost(PUBLIC);
        String boostId = null;
        for (int i = 0; i < 2; i++) {
            var body = mvc.perform(post(api(post) + "/reblog").with(user(bob)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reblog.id").value(post.id().toString()))
                    .andExpect(jsonPath("$.reblog.reblogged").value(true))
                    .andExpect(jsonPath("$.reblog.reblogs_count").value(1))
                    .andExpect(jsonPath("$.content").value(""))
                    .andExpect(jsonPath("$.visibility").value("public"))
                    .andExpect(jsonPath("$.account.id").value(bob.id().toString()))
                    .andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(body, "$.id");
            assertThat(boostId == null || boostId.equals(id)).isTrue();
            boostId = id;
        }
        mvc.perform(get(api(post)).with(user(alice))).andExpect(jsonPath("$.reblogs_count").value(1))
                .andExpect(jsonPath("$.reblogged").value(false));
        mvc.perform(get(api(post))).andExpect(jsonPath("$.reblogs_count").value(1));
        mvc.perform(get("/api/v1/statuses/" + boostId).with(user(alice)))
                .andExpect(jsonPath("$.reblog.id").value(post.id().toString()));
        mvc.perform(get("/api/v1/accounts/" + bob.id() + "/statuses"))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(boostId));
        mvc.perform(get("/api/v1/accounts/" + bob.id() + "/statuses").param("exclude_reblogs", "true"))
                .andExpect(jsonPath("$", empty()));
        mvc.perform(get("/api/v1/timelines/public"))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].id").value(post.id().toString()));
        follows.follow(alice, bob);
        mvc.perform(get("/api/v1/timelines/home").with(user(alice)))
                .andExpect(jsonPath("$[0].id").value(boostId))
                .andExpect(jsonPath("$[1].id").value(post.id().toString()));

        for (int i = 0; i < 2; i++) {
            mvc.perform(post(api(post) + "/unreblog").with(user(bob)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(post.id().toString()))
                    .andExpect(jsonPath("$.reblogged").value(false))
                    .andExpect(jsonPath("$.reblogs_count").value(0));
        }
        mvc.perform(get("/api/v1/accounts/" + bob.id() + "/statuses")).andExpect(jsonPath("$", empty()));
        mvc.perform(get("/api/v1/statuses/" + boostId).with(user(alice))).andExpect(status().isNotFound());
    }

    @Test
    void concurrentReblogsMakeOneBoost() throws Exception {
        var post = createPost(PUBLIC);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = IntStream.range(0, 16)
                    .mapToObj(i -> pool.submit(() -> boosts.reblog(post.id(), bob.id(), PUBLIC))).toList();
            for (var call : calls) assertThat(call.get()).isPresent();
        }
        assertThat(boostCount(post)).isEqualTo(1);
    }

    @Test
    void onlyPublicUnlistedAndOwnFollowersOnlyStatusesCanBeBoosted() throws Exception {
        var followersOnly = createPost(PRIVATE);
        follows.follow(bob, alice);
        mvc.perform(post(api(followersOnly) + "/reblog").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post(api(followersOnly) + "/reblog").with(user(alice)).param("visibility", "public"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("private"));
        var direct = statuses.create(alice.id(), new NewStatus("@boostbob secret", null, null, DIRECT, false, null));
        mvc.perform(post(api(direct) + "/reblog").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post(api(direct) + "/reblog").with(user(alice))).andExpect(status().isNotFound());

        var post = createPost(PUBLIC);
        mvc.perform(post(api(post) + "/reblog").with(user(bob)).param("visibility", "direct"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(api(post) + "/reblog").with(user(bob)).param("visibility", "nobody"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post(api(post) + "/reblog").with(user(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"unlisted\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("unlisted"));
        assertThat(boostCount(post)).isEqualTo(1);

        for (var action : List.of("reblog", "unreblog")) {
            mvc.perform(post(api(post) + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(post(api(post) + "/" + action).with(scoped(bob.id(), Set.of("write:favourites"))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(api(post) + "/" + action).with(scoped(null, Set.of("write:statuses"))))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(post("/api/v1/statuses/" + UUID.randomUUID() + "/" + action).with(user(bob)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void aBoostsIdStandsForTheStatusItBoosts() throws Exception {
        var post = createPost(PUBLIC);
        var boost = boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        var boostApi = "/api/v1/statuses/" + boost.id();
        mvc.perform(post(boostApi + "/reblog").with(user(alice)))
                .andExpect(jsonPath("$.reblog.id").value(post.id().toString()));
        mvc.perform(post(boostApi + "/favourite").with(user(alice)))
                .andExpect(jsonPath("$.id").value(post.id().toString()))
                .andExpect(jsonPath("$.favourites_count").value(1));
        var reply = mvc.perform(post("/api/v1/statuses").with(user(alice))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "a reply").param("in_reply_to_id", boost.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.in_reply_to_id").value(post.id().toString()))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get(boostApi + "/context").with(user(alice)))
                .andExpect(jsonPath("$.descendants[0].id").value(JsonPath.<String>read(reply, "$.id")));

        mvc.perform(delete(boostApi).with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(delete(boostApi).with(user(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reblog.id").value(post.id().toString()));
        assertThat(boostCount(post)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM status_tombstone WHERE status_id = :id").param("id", boost.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void boostsOfASuspendedAuthorsStatusesAreHidden() throws Exception {
        var post = createPost(PUBLIC);
        boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        actors.suspend(alice.id());
        try {
            mvc.perform(get("/api/v1/accounts/" + bob.id() + "/statuses")).andExpect(jsonPath("$", empty()));
        } finally {
            actors.unsuspend(alice.id());
        }
        mvc.perform(get("/api/v1/accounts/" + bob.id() + "/statuses")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void reblogNotificationsGroupByPostAndGoWithTheBoost() throws Exception {
        var post = createPost(PUBLIC);
        var remote = remote();
        handler.handle(announce(remote, post, "one", PUBLIC, ago(20)));
        var boost = boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        boosts.reblog(post.id(), alice.id(), PUBLIC).orElseThrow();
        settled();
        assertThat(notificationCount(post)).isEqualTo(2);
        mvc.perform(get("/api/v2/notifications").with(user(alice)).param("types[]", "reblog"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notification_groups", hasSize(1)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(2))
                .andExpect(jsonPath("$.notification_groups[0].type").value("reblog"))
                .andExpect(jsonPath("$.statuses[0].id").value(post.id().toString()));
        boosts.unreblog(post.id(), bob.id());
        assertThat(notificationCount(post)).isEqualTo(1);
        // A late or repeated event for a boost that's gone makes nothing.
        transaction.executeWithoutResult(tx ->
                projection.on(new StatusReblogged(boost.id(), bob.id(), post.id(), alice.id())));
        assertThat(notificationCount(post)).isEqualTo(1);
    }

    @Test
    void receivesAnnouncesOfLocalStatusesAndUndoesThem() throws Exception {
        var post = createPost(UNLISTED);
        var remote = remote();
        var announce = announce(remote, post, "one", UNLISTED, ago(20));
        handler.handle(announce);
        handler.handle(announce);
        assertThat(boostCount(post)).isEqualTo(1);
        mvc.perform(get(api(post))).andExpect(jsonPath("$.reblogs_count").value(1));
        var stored = jdbc.sql("SELECT visibility FROM status WHERE uri = :uri").param("uri", announce.id())
                .query(String.class).single();
        assertThat(stored).isEqualTo("unlisted");

        undos.handle(undo(remote, announce.id(), ago(10)));
        assertThat(boostCount(post)).isZero();
        handler.handle(announce);
        assertThat(boostCount(post)).isZero();
    }

    @Test
    void anUndoBeforeItsAnnounceStopsIt() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var announce = announce(remote, post, "early", PUBLIC, ago(10));
        undos.handle(undo(remote, Map.of("id", announce.id(), "type", "Announce", "actor", remote.uri(),
                "object", post.uri()), ago(5)));
        handler.handle(announce);
        assertThat(boostCount(post)).isZero();
    }

    @Test
    void aNewerAnnounceReplacesTheBoostsIdentitySoOnlyItsUndoRemovesIt() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var first = announce(remote, post, "first", PUBLIC, ago(30));
        var second = announce(remote, post, "second", PUBLIC, ago(20));
        handler.handle(first);
        handler.handle(second);
        // A retry of the older one doesn't take the identity back.
        handler.handle(first);
        assertThat(boostCount(post)).isEqualTo(1);
        undos.handle(undo(remote, first.id(), ago(10)));
        assertThat(boostCount(post)).isEqualTo(1);
        undos.handle(undo(remote, second.id(), ago(5)));
        assertThat(boostCount(post)).isZero();
    }

    @Test
    void onlyTheBoosterCanWithdrawABoost() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var announce = announce(remote, post, "one", PUBLIC, ago(20));
        handler.handle(announce);
        var sameServer = actors.upsertRemote(new RemoteActorProfile(URI.create(remote.uri() + "-other"), "other",
                remote.domain(), URI.create(remote.uri() + "-other/inbox"), null, null, null));
        undos.handle(undo(sameServer, announce.id(), ago(10)));
        undos.handle(undo(remote(), announce.id(), ago(10)));
        deletes.handle(activity(sameServer, "Delete", announce.id(), ago(10)));
        assertThat(boostCount(post)).isEqualTo(1);

        deletes.handle(activity(remote, "Delete", announce.id(), ago(5)));
        assertThat(boostCount(post)).isZero();
    }

    @Test
    void ignoresAnnouncesThatCantBeBoosts() {
        var remote = remote();
        handler.handle(announce(remote, createPost(PRIVATE), "private", PUBLIC, ago(10)));
        handler.handle(announce(remote, createPost(DIRECT), "direct", PUBLIC, ago(10)));
        var post = createPost(PUBLIC);
        handler.handle(announce(remote, post, "to-nobody", DIRECT, ago(10)));
        handler.handle(announce(remote, remotePost(remote()), "remote", PUBLIC, ago(10)));
        // A boost isn't something to boost.
        var boost = boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        handler.handle(announce(remote, boost, "boost", PUBLIC, ago(10)));
        assertThat(jdbc.sql("SELECT count(*) FROM status s JOIN actor a ON a.id = s.actor_id WHERE a.id = :id")
                .param("id", remote.id()).query(Long.class).single()).isZero();
    }

    @Test
    void sendsAnnouncesAndUndoesThemToFollowersAndTheRemoteAuthor() throws Exception {
        var author = remote();
        var post = remotePost(author);
        var follower = remote();
        follow(follower, bob);
        var boost = boosts.reblog(post.id(), bob.id(), UNLISTED).orElseThrow();
        settled();
        var sent = outbound(boost.uri().toString());
        assertThat(JsonPath.<String>read(sent, "$.type")).isEqualTo("Announce");
        assertThat(JsonPath.<String>read(sent, "$.actor")).isEqualTo(bob.uri().toString());
        assertThat(JsonPath.<String>read(sent, "$.object")).isEqualTo(post.uri().toString());
        assertThat(JsonPath.<List<String>>read(sent, "$.to")).containsExactly(bob.uri() + "/followers");
        assertThat(JsonPath.<List<String>>read(sent, "$.cc")).containsExactlyInAnyOrder(PUBLIC_COLLECTION, author.uri().toString());
        assertThat(boost.uri().toString()).isEqualTo(bob.uri() + "/statuses/" + boost.id() + "/activity");
        assertThat(deliveryInboxes(boost.uri().toString()))
                .containsExactlyInAnyOrder(author.inboxUri().toString(), follower.inboxUri().toString());

        boosts.unreblog(post.id(), bob.id());
        settled();
        assertThat(outboundCount(boost.uri().toString())).isZero();
        var undo = outbound(boost.uri() + "/undo");
        assertThat(JsonPath.<String>read(undo, "$.type")).isEqualTo("Undo");
        assertThat(JsonPath.<String>read(undo, "$.object.type")).isEqualTo("Announce");
        assertThat(JsonPath.<String>read(undo, "$.object.id")).isEqualTo(boost.uri().toString());
        assertThat(deliveryInboxes(boost.uri() + "/undo"))
                .containsExactlyInAnyOrder(author.inboxUri().toString(), follower.inboxUri().toString());

        // A late listener for a boost that's gone sends nothing.
        jdbc.sql("DELETE FROM outbound_activity").update();
        transaction.executeWithoutResult(tx ->
                deliveries.on(new StatusReblogged(boost.id(), bob.id(), post.id(), author.id())));
        assertThat(outboundCount(boost.uri().toString())).isZero();
    }

    @Test
    void deletingABoostedStatusWithdrawsItsLocalBoosts() {
        var author = remote();
        var post = remotePost(author);
        var follower = remote();
        follow(follower, bob);
        var remoteBoost = remotePost(author);
        var boost = boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        boosts.reblog(remoteBoost.id(), bob.id(), PUBLIC).orElseThrow();
        settled();

        // Only the author can delete it, and nothing is withdrawn until it's gone.
        statuses.deleteRemote(post.uri(), remote().uri());
        settled();
        assertThat(outboundCount(boost.uri() + "/undo")).isZero();

        statuses.deleteRemote(post.uri(), author.uri());
        settled();
        assertThat(outboundCount(boost.uri() + "/undo")).isEqualTo(1);
        assertThat(deliveryInboxes(boost.uri() + "/undo")).contains(follower.inboxUri().toString());

        var local = createPost(PUBLIC);
        var localBoost = boosts.reblog(local.id(), bob.id(), PUBLIC).orElseThrow();
        settled();
        statuses.delete(local.id(), alice.id());
        settled();
        assertThat(outboundCount(localBoost.uri() + "/undo")).isEqualTo(1);
        // The Delete reaches the booster's followers too, who could have it from the boost.
        assertThat(deliveryInboxes(local.uri() + "#delete")).contains(follower.inboxUri().toString());
    }

    @Test
    void servesSharesOfPublicStatusesOnly() throws Exception {
        var post = createPost(PUBLIC);
        var remote = remote();
        var announce = announce(remote, post, "one", PUBLIC, ago(20));
        handler.handle(announce);
        boosts.reblog(post.id(), bob.id(), UNLISTED).orElseThrow();
        boosts.reblog(post.id(), alice.id(), PRIVATE).orElseThrow();
        mvc.perform(get(post.uri().getPath()).accept(AP))
                .andExpect(jsonPath("$.shares").value(post.uri() + "/shares"));
        mvc.perform(get(post.uri().getPath() + "/shares").accept(AP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OrderedCollection"))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get(post.uri().getPath() + "/shares").param("page", "true").accept(AP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedItems", hasSize(2)))
                .andExpect(jsonPath("$.orderedItems[0].type").value("Announce"))
                .andExpect(jsonPath("$.orderedItems[0].actor").value(bob.uri().toString()))
                .andExpect(jsonPath("$.orderedItems[0].cc[0]").value(PUBLIC_COLLECTION))
                .andExpect(jsonPath("$.orderedItems[1].id").value(announce.id()))
                .andExpect(jsonPath("$.orderedItems[1].to[0]").value(PUBLIC_COLLECTION));
        mvc.perform(get(post.uri().getPath() + "/shares").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(post.uri().toString()));
        mvc.perform(get(createPost(PRIVATE).uri().getPath() + "/shares").accept(AP)).andExpect(status().isNotFound());
        mvc.perform(get(post.uri().getPath() + "/shares").param("page", "true").param("max_id", "nope").accept(AP))
                .andExpect(status().isBadRequest());
    }

    @Test
    void boostsShowOnProfilePagesAndLeadToWhatTheyBoost() throws Exception {
        var post = createPost(PUBLIC);
        var boost = boosts.reblog(post.id(), bob.id(), PUBLIC).orElseThrow();
        var remoteBoost = boosts.reblog(remotePost(remote()).id(), bob.id(), PUBLIC).orElseThrow();
        mvc.perform(get("/users/boostbob").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("boosted")))
                .andExpect(content().string(containsString("a post")))
                .andExpect(content().string(containsString("remote")));
        var path = "/users/boostbob/statuses/" + boost.id();
        mvc.perform(get(path).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(post.uri().getPath()));
        mvc.perform(get("/users/boostbob/statuses/" + remoteBoost.id()).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("https://r*.test/users/remote/posts/*"));
        mvc.perform(get("/users/boostalice/statuses/" + boost.id()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).accept(AP)).andExpect(status().isNotFound());
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote() {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/remote");
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", host, URI.create(uri + "/inbox"), null, null, null));
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

    private Status createPost(Visibility visibility) {
        return statuses.create(alice.id(), new NewStatus("a post", null, null, visibility, false, null));
    }

    private Status remotePost(Actor author) {
        var id = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/posts/" + UUID.randomUUID()), null,
                author.id(), "<p>remote</p>", null, null, PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findVisible(id, Optional.empty()).orElseThrow();
    }

    private InboundActivity announce(Actor actor, Status status, String suffix, Visibility visibility, Instant at) {
        var id = actor.uri() + "/announces/" + suffix;
        var followers = actor.uri() + "/followers";
        var body = new java.util.LinkedHashMap<String, Object>(Map.of(
                "id", id, "type", "Announce", "actor", actor.uri(), "object", status.uri(),
                "published", at.toString()));
        switch (visibility) {
            case PUBLIC -> {
                body.put("to", List.of(PUBLIC_COLLECTION));
                body.put("cc", List.of(followers));
            }
            case UNLISTED -> {
                body.put("to", List.of(followers));
                body.put("cc", List.of(PUBLIC_COLLECTION));
            }
            case PRIVATE -> body.put("to", List.of(followers));
            case DIRECT -> body.put("to", List.of(alice.uri().toString()));
        }
        return new InboundActivity(id, actor.uri().toString(), "Announce", json.writeValueAsString(body), at);
    }

    private InboundActivity undo(Actor actor, Object object, Instant at) {
        return activity(actor, "Undo", object, at);
    }

    private InboundActivity activity(Actor actor, String type, Object object, Instant at) {
        var id = actor.uri() + "/activities/" + UUID.randomUUID();
        return new InboundActivity(id, actor.uri().toString(), type,
                json.writeValueAsString(Map.of("id", id, "type", type, "actor", actor.uri(), "object", object)), at);
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

    private long boostCount(Status post) {
        return jdbc.sql("SELECT count(*) FROM status WHERE reblog_of_id = :id").param("id", post.id())
                .query(Long.class).single();
    }

    private String outbound(String id) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE activity_id = :id").param("id", id).query(String.class).single();
    }

    private long outboundCount(String id) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id").param("id", id).query(Long.class).single();
    }

    private List<String> deliveryInboxes(String activityId) {
        return jdbc.sql("SELECT task_data FROM scheduled_tasks WHERE task_name = 'activity-delivery'")
                .query((rs, row) -> new String(rs.getBytes("task_data")))
                .list().stream()
                .filter(data -> activityId.equals(JsonPath.read(data, "$.activityId")))
                .map(data -> JsonPath.<String>read(data, "$.inbox"))
                .toList();
    }

    private long notificationCount(Status post) {
        return jdbc.sql("SELECT count(*) FROM inbox_notification WHERE status_id = :id AND type = 'reblog'")
                .param("id", post.id()).query(Long.class).single();
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }
}
