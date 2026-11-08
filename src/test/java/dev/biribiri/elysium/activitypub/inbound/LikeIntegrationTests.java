package dev.biribiri.elysium.activitypub.inbound;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.outbound.LikeDeliveries;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusLiked;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusUnliked;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.content.internal.LikeRepository;
import dev.biribiri.elysium.graph.FollowService;
import dev.biribiri.elysium.inbox.InboxProjection;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static dev.biribiri.elysium.content.Visibility.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doAnswer;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://likes.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class LikeIntegrationTests {
    private static final String AP = "application/activity+json";
    private static final Set<String> SCOPES = Set.of("read:statuses", "write:favourites", "read:notifications", "write:notifications");
    @Autowired MockMvc mvc;
    @Autowired ActorService actors;
    @Autowired StatusService statuses;
    @Autowired LikeService likes;
    @MockitoSpyBean LikeRepository repository;
    @Autowired LikeHandler handler;
    @Autowired UndoHandler undos;
    @Autowired InboundActivities inbound;
    @Autowired FollowService follows;
    @Autowired LikeDeliveries deliveries;
    @Autowired InboxProjection projection;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired DomainBlocks blocks;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM domain_block").update();
        alice = local("likealice");
        bob = local("likebob");
    }

    @Test
    void favouritesAreIdempotentAndViewerStateAppearsAcrossTheApi() throws Exception {
        var post = createPost(PUBLIC);
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(api(post) + "/favourite").with(user(bob)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.favourited").value(true))
                    .andExpect(jsonPath("$.favourites_count").value(1));
        }
        mvc.perform(get(api(post)).with(user(bob))).andExpect(jsonPath("$.favourited").value(true));
        mvc.perform(get(api(post)).with(user(alice))).andExpect(jsonPath("$.favourited").value(false));
        mvc.perform(get(api(post))).andExpect(jsonPath("$.favourited").value(false))
                .andExpect(jsonPath("$.favourites_count").value(1));
        mvc.perform(get("/api/v1/timelines/public").with(user(bob)))
                .andExpect(jsonPath("$[0].favourited").value(true));
        mvc.perform(get("/api/v1/accounts/" + alice.id() + "/statuses").with(user(bob)))
                .andExpect(jsonPath("$[0].favourited").value(true));
        var reply = statuses.create(alice.id(), new NewStatus("reply", null, null, PUBLIC, false, post.id()));
        mvc.perform(get(api(reply) + "/context").with(user(bob)))
                .andExpect(jsonPath("$.ancestors[0].favourited").value(true));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(api(post) + "/unfavourite").with(user(bob)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.favourited").value(false))
                    .andExpect(jsonPath("$.favourites_count").value(0));
        }
        mvc.perform(post(api(post) + "/favourite").with(user(bob))).andExpect(jsonPath("$.favourites_count").value(1));
    }

    @Test
    void requiresTheFavouriteScopeAndAUserAndDoesNotRevealHiddenStatuses() throws Exception {
        var post = createPost(PRIVATE);
        for (var action : List.of("favourite", "unfavourite")) {
            mvc.perform(post(api(post) + "/" + action)).andExpect(status().isUnauthorized());
            mvc.perform(post(api(post) + "/" + action).with(scoped(bob.id(), Set.of("write:statuses"))))
                    .andExpect(status().isForbidden());
            mvc.perform(post(api(post) + "/" + action).with(scoped(null, Set.of("write:favourites"))))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(post(api(post) + "/" + action).with(user(bob))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/statuses/not-an-id/" + action).with(user(bob))).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/statuses/" + UUID.randomUUID() + "/" + action).with(user(bob)))
                    .andExpect(status().isNotFound());
        }
        follows.follow(bob, alice);
        mvc.perform(post(api(post) + "/favourite").with(user(bob))).andExpect(status().isOk());
        var direct = statuses.create(alice.id(), new NewStatus("@likebob secret", null, null, DIRECT, false, null));
        mvc.perform(post(api(direct) + "/favourite").with(user(bob))).andExpect(status().isOk());
        mvc.perform(post(api(createPost(DIRECT)) + "/favourite").with(user(bob))).andExpect(status().isNotFound());
    }

    @Test
    void concurrentFavouritesHaveOneIdentityAndOneNotification() throws Exception {
        var post = createPost(PUBLIC);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = IntStream.range(0, 16).mapToObj(i -> pool.submit(() -> likes.favourite(post.id(), bob.id()))).toList();
            for (var call : calls) assertThat(call.get()).isPresent();
        }
        assertThat(likes.count(post.id())).isEqualTo(1);
        settled();
        assertThat(notificationCount(post)).isEqualTo(1);
    }

    @Test
    void favouriteNotificationsGroupByPostAndUnlikeAndDismissalSurviveRetries() throws Exception {
        var post = createPost(PUBLIC);
        var remote = remote();
        likes.favourite(post.id(), bob.id());
        handler.handle(like(remote, post, "one", ago(20)));
        likes.favourite(post.id(), alice.id());
        settled();
        assertThat(notificationCount(post)).isEqualTo(2);
        mvc.perform(get("/api/v2/notifications").with(user(alice)).param("types[]", "favourite"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notification_groups", hasSize(1)))
                .andExpect(jsonPath("$.notification_groups[0].notifications_count").value(2))
                .andExpect(jsonPath("$.notification_groups[0].type").value("favourite"))
                .andExpect(jsonPath("$.statuses[0].favourited").value(true));
        var saved = likes.page(post.id(), null, 10).stream().filter(l -> l.actorId().equals(bob.id())).findFirst().orElseThrow();
        mvc.perform(post("/api/v1/notifications/clear").with(user(alice))).andExpect(status().isOk());
        transaction.executeWithoutResult(tx -> projection.on(new StatusLiked(saved.id(), bob.id(), post.id(), alice.id())));
        mvc.perform(get("/api/v1/notifications").with(user(alice))).andExpect(jsonPath("$", empty()));
        likes.unfavourite(post.id(), bob.id());
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_notification WHERE like_id = :id").param("id", saved.id())
                .query(Long.class).single()).isZero();
        likes.favourite(post.id(), bob.id());
        settled();
        mvc.perform(get("/api/v1/notifications").with(user(alice))).andExpect(jsonPath("$", hasSize(1)));
        likes.unfavourite(post.id(), bob.id());
        transaction.executeWithoutResult(tx -> projection.on(new StatusLiked(saved.id(), bob.id(), post.id(), alice.id())));
        mvc.perform(get("/api/v1/notifications").with(user(alice))).andExpect(jsonPath("$", empty()));
    }

    @Test
    void receivesLikesAndUndoesThemByIdAfterInboundHistoryHasExpired() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var like = like(remote, post, "one", ago(20));
        inbound.accept(like);
        handler.handle(like);
        handler.handle(like);
        assertThat(likes.count(post.id())).isEqualTo(1);
        jdbc.sql("DELETE FROM inbound_activity WHERE activity_id = :id").param("id", like.id()).update();
        undos.handle(undo(remote, like.id(), ago(10)));
        assertThat(likes.count(post.id())).isZero();
        handler.handle(like);
        assertThat(likes.count(post.id())).isZero();
    }

    @Test
    void undoBeforeLikeWorksForIdOnlyEmbeddedAndIdlessObjects() {
        var remote = remote();
        for (int shape = 0; shape < 3; shape++) {
            var post = createPost(PUBLIC);
            var like = like(remote, post, "early" + shape, ago(20));
            Object object = switch (shape) {
                case 0 -> like.id();
                case 1 -> Map.of("id", like.id(), "type", "Like", "actor", remote.uri(), "object", post.uri());
                default -> Map.of("type", "Like", "actor", remote.uri(), "object", post.uri());
            };
            undos.handle(undo(remote, object, ago(10)));
            handler.handle(like);
            assertThat(likes.count(post.id())).isZero();
            handler.handle(like(remote, post, "later" + shape, ago(5)));
            assertThat(likes.count(post.id())).isEqualTo(1);
        }
    }

    @Test
    void oldUndosAndRepeatedLikesDoNotReplaceOrRemoveANewerLike() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var older = like(remote, post, "older", ago(40));
        var newer = like(remote, post, "newer", ago(20));
        inbound.accept(older);
        handler.handle(newer);
        handler.handle(older);
        undos.handle(undo(remote, Map.of("id", older.id(), "type", "Like", "actor", remote.uri(), "object", post.uri()), ago(10)));
        assertThat(likes.count(post.id())).isEqualTo(1);
        assertThat(likes.page(post.id(), null, 10).getFirst().activityUri()).isEqualTo(newer.id());
        undos.handle(undo(remote, newer.id(), ago(5)));
        handler.handle(older);
        handler.handle(newer);
        assertThat(likes.count(post.id())).isZero();
    }

    @Test
    void anIdOnlyUndoCannotDeleteAReplacementCommittedAfterItReadTheOldLike() throws Exception {
        var post = createPost(PUBLIC);
        var remote = remote();
        var older = like(remote, post, "old-racing", ago(30));
        var newer = like(remote, post, "new-racing", ago(10));
        var reads = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            doAnswer(call -> {
                var result = call.callRealMethod();
                var read = reads.incrementAndGet();
                if (read == 1) pool.submit(() -> handler.handle(older)).get(10, TimeUnit.SECONDS);
                if (read == 2) pool.submit(() -> handler.handle(newer)).get(10, TimeUnit.SECONDS);
                return result;
            }).when(repository).findActivity(remote.uri().toString(), older.id());
            likes.undo(remote.uri().toString(), older.id(), Optional.empty(), Optional.empty(), ago(5));
        }
        assertThat(likes.page(post.id(), null, 10)).hasSize(1);
        assertThat(likes.page(post.id(), null, 10).getFirst().activityUri()).isEqualTo(newer.id());
    }

    @Test
    void onlyTheLikerCanUndoAndTargetsMustBeLocalVisibleStatuses() {
        var post = createPost(PUBLIC);
        var remote = remote();
        var other = actors.upsertRemote(new RemoteActorProfile(URI.create("https://" + remote.domain() + "/users/other"),
                "other", remote.domain(), URI.create("https://" + remote.domain() + "/other-inbox"), null, null, null));
        var like = like(remote, post, "owned", ago(20));
        inbound.accept(like);
        handler.handle(like);
        undos.handle(undo(other, like.id(), ago(10)));
        undos.handle(undo(remote, Map.of("id", like.id(), "type", "Like", "actor", other.uri(), "object", post.uri()), ago(10)));
        undos.handle(undo(remote, "https://unrelated.example/like", ago(10)));
        assertThat(likes.count(post.id())).isEqualTo(1);
        var hidden = createPost(PRIVATE);
        handler.handle(like(remote, hidden, "hidden", ago(10)));
        assertThat(likes.count(hidden.id())).isZero();
        transaction.executeWithoutResult(tx -> follows.accept(remote.id(), remote.uri(), alice.id(),
                remote.uri() + "/follow", new FollowService.Arrival(ago(20), ago(20))));
        handler.handle(like(remote, hidden, "visible", ago(5)));
        assertThat(likes.count(hidden.id())).isEqualTo(1);
        var direct = statuses.create(alice.id(), new NewStatus("@remote@" + remote.domain() + " secret", null, null,
                DIRECT, false, null));
        handler.handle(like(remote, direct, "direct-visible", ago(5)));
        assertThat(likes.count(direct.id())).isEqualTo(1);
        var remotePost = remotePost(remote);
        handler.handle(like(remote, remotePost, "remote", ago(5)));
        assertThat(likes.count(remotePost.id())).isZero();
        for (Object object : List.of(Map.of("type", "Note"), List.of(post.uri(), hidden.uri()), "not a uri")) {
            handler.handle(activity(remote, "Like", object, ago(5)));
        }
    }

    @Test
    void remotePostLikesDeliverEmbeddedUndoAndDoNotResurrectWhenListenersRunLate() {
        var remote = remote();
        var post = remotePost(remote);
        likes.favourite(post.id(), bob.id());
        var saved = likes.page(post.id(), null, 10).getFirst();
        settled();
        var body = json.readTree(outbound(saved.activityUri()));
        assertThat(body.path("type").asString()).isEqualTo("Like");
        assertThat(body.path("actor").asString()).isEqualTo(bob.uri().toString());
        assertThat(body.path("object").asString()).isEqualTo(post.uri().toString());
        assertThat(body.path("to").get(0).asString()).isEqualTo(remote.uri().toString());
        likes.unfavourite(post.id(), bob.id());
        settled();
        var undo = json.readTree(outbound(saved.activityUri() + "/undo"));
        assertThat(undo.path("type").asString()).isEqualTo("Undo");
        assertThat(undo.path("object").path("id").asString()).isEqualTo(saved.activityUri());
        assertThat(undo.path("object").has("@context")).isFalse();
        assertThat(outboundCount(saved.activityUri())).isZero();
        transaction.executeWithoutResult(tx -> deliveries.on(new StatusLiked(saved.id(), bob.id(), post.id(), remote.id())));
        assertThat(outboundCount(saved.activityUri())).isZero();
        likes.favourite(post.id(), bob.id());
        var next = likes.page(post.id(), null, 10).getFirst();
        assertThat(next.activityUri()).isNotEqualTo(saved.activityUri());
        transaction.executeWithoutResult(tx -> deliveries.on(new StatusUnliked(bob.id(), post.id(), remote.id(), post.uri(), saved.activityUri())));
        settled();
        assertThat(outboundCount(next.activityUri())).isEqualTo(1);
    }

    @Test
    void collectionContainsPagedLikeActivitiesAndNegotiatesLikeANote() throws Exception {
        var post = createPost(UNLISTED);
        likes.favourite(post.id(), bob.id());
        for (int i = 0; i < 40; i++) {
            var remote = remote();
            likes.receive(remote.id(), post.id(), remote.uri() + "/like", ago(10));
        }
        var url = post.uri() + "/likes";
        mvc.perform(get(post.uri()).accept(AP)).andExpect(jsonPath("$.likes").value(url));
        mvc.perform(get(url).accept(AP)).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(41))
                .andExpect(jsonPath("$.first").value(url + "?page=true"))
                .andExpect(header().stringValues("Vary", hasItem("Accept")));
        var first = mvc.perform(get(url).accept(AP).param("page", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderedItems", hasSize(40)))
                .andExpect(jsonPath("$.orderedItems[0].type").value("Like"))
                .andExpect(jsonPath("$.orderedItems[0].object").value(post.uri().toString()))
                .andReturn().getResponse().getContentAsString();
        String next = JsonPath.read(first, "$.next");
        mvc.perform(get(next).accept(AP)).andExpect(jsonPath("$.orderedItems", hasSize(1)))
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(get(url).accept("application/ld+json; profile=\"https://www.w3.org/ns/activitystreams\""))
                .andExpect(status().isOk());
        for (String accept : List.of("text/html", "*/*")) {
            mvc.perform(get(url).accept(accept)).andExpect(status().isFound())
                    .andExpect(redirectedUrl(post.uri().toString()));
        }
        mvc.perform(get(url)).andExpect(status().isFound());
        mvc.perform(get(url).accept("application/json")).andExpect(status().isNotAcceptable());
        mvc.perform(get(url).accept(AP).param("page", "true").param("max_id", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(options(url).header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mvc.perform(get(url.toString().replace("likealice", "likebob")).accept(AP)).andExpect(status().isNotFound());
        for (var visibility : List.of(PRIVATE, DIRECT)) {
            var hidden = createPost(visibility);
            mvc.perform(get(hidden.uri() + "/likes").accept(AP)).andExpect(status().isNotFound());
            mvc.perform(get(hidden.uri() + "/likes").accept("text/html")).andExpect(status().isNotFound());
        }
        statuses.delete(post.id(), alice.id());
        mvc.perform(get(url).accept(AP)).andExpect(status().isNotFound());
        mvc.perform(get(url).accept("text/html")).andExpect(status().isNotFound());
    }

    @Test
    void moderationFiltersCountsCollectionsAndNotificationsAndSuspensionIsReversible() throws Exception {
        var post = createPost(PUBLIC);
        var remote = remote();
        handler.handle(like(remote, post, "one", ago(10)));
        settled();
        actors.suspend(remote.id());
        settled();
        assertThat(likes.count(post.id())).isZero();
        mvc.perform(get(post.uri() + "/likes").accept(AP).param("page", "true"))
                .andExpect(jsonPath("$.orderedItems", empty()));
        mvc.perform(get("/api/v1/notifications").with(user(alice))).andExpect(jsonPath("$", empty()));
        actors.unsuspend(remote.id());
        settled();
        assertThat(likes.count(post.id())).isEqualTo(1);
        actors.suspend(alice.id());
        try {
            mvc.perform(get(post.uri() + "/likes").accept(AP)).andExpect(status().isForbidden());
            mvc.perform(get(post.uri() + "/likes").accept("text/html")).andExpect(status().isForbidden());
        } finally {
            actors.unsuspend(alice.id());
        }
        blocks.create(remote.domain(), "test");
        assertThat(likes.count(post.id())).isZero();
        settled();
        assertThat(likes.page(post.id(), null, 10)).isEmpty();
        likes.removeForDomain(remote.domain());
        assertThat(jdbc.sql("SELECT count(*) FROM status_like WHERE actor_id = :actor").param("actor", remote.id())
                .query(Long.class).single()).isZero();
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote() {
        var host = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/remote");
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", host, URI.create(uri + "/inbox"), null, null, null));
    }

    private Status createPost(Visibility visibility) {
        return statuses.create(alice.id(), new NewStatus("a post", null, null, visibility, false, null));
    }

    private Status remotePost(Actor author) {
        var id = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/posts/" + UUID.randomUUID()), null,
                author.id(), "<p>remote</p>", null, null, PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findVisible(id, Optional.empty()).orElseThrow();
    }

    private InboundActivity like(Actor actor, Status status, String suffix, Instant at) {
        return new InboundActivity(actor.uri() + "/likes/" + suffix, actor.uri().toString(), "Like",
                json.writeValueAsString(Map.of("id", actor.uri() + "/likes/" + suffix, "type", "Like", "actor", actor.uri(), "object", status.uri())), at);
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

    private String outbound(String id) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE activity_id = :id").param("id", id).query(String.class).single();
    }

    private long outboundCount(String id) {
        return jdbc.sql("SELECT count(*) FROM outbound_activity WHERE activity_id = :id").param("id", id).query(Long.class).single();
    }

    private long notificationCount(Status post) {
        return jdbc.sql("SELECT count(*) FROM inbox_notification WHERE status_id = :id AND type = 'favourite'")
                .param("id", post.id()).query(Long.class).single();
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql("SELECT count(*) FROM event_publication WHERE completion_date IS NULL")
                .query(Long.class).single() == 0);
    }
}
