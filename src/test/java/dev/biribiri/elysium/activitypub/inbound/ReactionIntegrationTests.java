package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.TestImages;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.CustomEmojis;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.ReactionService;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static dev.biribiri.elysium.content.Visibility.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Emoji reactions through Pleroma's API, from other servers, and to them. Handlers are called directly with no job
 * worker running, as in {@link LikeIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.instance.base-url=https://reactions.example", "elysium.jobs.worker-enabled=false"})
@AutoConfigureMockMvc
class ReactionIntegrationTests {

    private static final Set<String> SCOPES =
            Set.of("read:statuses", "write:favourites", "read:notifications", "write:notifications");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private ReactionService reactions;
    @Autowired
    private LikeService likes;
    @Autowired
    private CustomEmojis emojis;
    @Autowired
    private EmojiReactHandler reactHandler;
    @Autowired
    private LikeHandler likeHandler;
    @Autowired
    private UndoHandler undoHandler;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;

    private Actor alice;
    private Actor bob;

    @BeforeEach
    void setUp() {
        settled();
        alice = local("reactalice");
        bob = local("reactbob");
    }

    @Test
    void reactsThroughTheApiWithSeveralEmojiOnceEach() throws Exception {
        var post = createPost();
        var blob = emojis.addLocal(shortcode(), TestImages.png(32, 32), null);

        for (var i = 0; i < 2; i++) {
            mvc.perform(put(path(post, "👍")).with(user(bob)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.favourited").value(false))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions", hasSize(1)))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[0].name").value("👍"))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[0].count").value(1))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[0].me").value(true));
        }
        mvc.perform(put(path(post, ":" + blob.shortcode() + ":")).with(user(bob))).andExpect(status().isOk());
        mvc.perform(put(path(post, "👍")).with(user(alice))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/statuses/" + post.id()))
                .andExpect(jsonPath("$.favourites_count").value(0))
                .andExpect(jsonPath("$.pleroma.emoji_reactions", hasSize(2)))
                .andExpect(jsonPath("$.pleroma.emoji_reactions[0].count").value(2))
                .andExpect(jsonPath("$.pleroma.emoji_reactions[0].me").value(false))
                .andExpect(jsonPath("$.pleroma.emoji_reactions[1].name").value(blob.shortcode()))
                .andExpect(jsonPath("$.pleroma.emoji_reactions[1].url", startsWith("https://reactions.example/media/")));

        mvc.perform(get("/api/v1/pleroma/statuses/" + post.id() + "/reactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].accounts", hasSize(2)))
                .andExpect(jsonPath("$[0].accounts[0].id").value(alice.id().toString()));
        mvc.perform(get(path(post, blob.shortcode())))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accounts[0].id").value(bob.id().toString()));

        for (var i = 0; i < 2; i++) {
            // Alice's is the only one left, so 👍 now comes after the emoji Bob used before it.
            mvc.perform(delete(path(post, "👍")).with(user(bob)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[1].name").value("👍"))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[1].count").value(1))
                    .andExpect(jsonPath("$.pleroma.emoji_reactions[1].me").value(false));
        }
    }

    @Test
    void refusesWhatIsntAnEmojiAndMoreThanTheLimit() throws Exception {
        var post = createPost();

        mvc.perform(put(path(post, "hi")).with(user(bob))).andExpect(status().isUnprocessableContent());
        mvc.perform(put(path(post, "👍👍")).with(user(bob))).andExpect(status().isUnprocessableContent());
        mvc.perform(put(path(post, ":no_such_emoji:")).with(user(bob))).andExpect(status().isUnprocessableContent());
        mvc.perform(put(path(post, "👍")).with(scoped(bob.id(), Set.of("read:statuses"))))
                .andExpect(status().isForbidden());
        mvc.perform(put(URI.create("/api/v1/pleroma/statuses/" + UUID.randomUUID() + "/reactions/" + encode("👍")))
                        .with(user(bob)))
                .andExpect(status().isNotFound());

        var many = List.of("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍",
                "🥰", "😘", "😗", "😙", "😚", "😋");
        for (var i = 0; i < ReactionService.MAX_PER_ACCOUNT; i++) {
            mvc.perform(put(path(post, many.get(i))).with(user(bob))).andExpect(status().isOk());
        }
        mvc.perform(put(path(post, many.get(ReactionService.MAX_PER_ACCOUNT))).with(user(bob)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theAuthorIsNotifiedWithTheEmoji() throws Exception {
        var post = createPost();
        var blob = emojis.addLocal(shortcode(), TestImages.png(32, 32), null);

        mvc.perform(put(path(post, blob.shortcode())).with(user(bob))).andExpect(status().isOk());
        settled();

        mvc.perform(get("/api/v1/notifications").with(user(alice)))
                .andExpect(jsonPath("$[0].type").value("pleroma:emoji_reaction"))
                .andExpect(jsonPath("$[0].account.id").value(bob.id().toString()))
                .andExpect(jsonPath("$[0].status.id").value(post.id().toString()))
                .andExpect(jsonPath("$[0].emoji").value(blob.shortcode()))
                .andExpect(jsonPath("$[0].emoji_url", startsWith("https://reactions.example/media/")));
    }

    @Test
    void receivesEmojiReactsAndMisskeysLikesAsReactions() {
        var post = createPost();
        var remote = remote();
        var host = remote.uri().getHost();

        reactHandler.handle(activity(remote, "EmojiReact", post, Map.of("content", "❤️"), ago(30)));
        var custom = new HashMap<String, Object>();
        custom.put("content", ":blobcat:");
        custom.put("tag", List.of(Map.of("type", "Emoji", "name", ":blobcat:",
                "icon", Map.of("type", "Image", "url", "https://" + host + "/blobcat.png"))));
        reactHandler.handle(activity(remote, "EmojiReact", post, custom, ago(20)));
        likeHandler.handle(activity(remote, "Like", post, Map.of("_misskey_reaction", "🎉", "content", "🎉"), ago(10)));
        // A custom emoji with no image isn't a reaction, and an EmojiReact that isn't one is nothing.
        reactHandler.handle(activity(remote, "EmojiReact", post, Map.of("content", ":nothing:"), ago(5)));

        var summaries = reactions.summaries(List.of(post.id()), Optional.empty()).get(post.id());
        assertThat(summaries).extracting(summary -> summary.name())
                .containsExactly("❤️", "blobcat@" + remote.domain(), "🎉");
        assertThat(summaries.get(1).emoji().domain()).isEqualTo(remote.domain());
        assertThat(likes.count(post.id())).isZero();

        // A Like with no emoji is still a favourite.
        likeHandler.handle(activity(remote, "Like", post, Map.of(), ago(1)));
        assertThat(likes.count(post.id())).isEqualTo(1);
    }

    @Test
    void anUndoWithdrawsAReactionEvenIfItArrivesFirst() {
        var post = createPost();
        var remote = remote();
        var react = activity(remote, "EmojiReact", post, Map.of("content", "👍"), ago(30));
        var misskey = activity(remote, "Like", post, Map.of("_misskey_reaction", "🎉"), ago(20));
        reactHandler.handle(react);
        likeHandler.handle(misskey);

        undoHandler.handle(undo(remote, Map.of("id", react.id(), "type", "EmojiReact", "actor", remote.uri().toString(),
                "object", post.uri().toString())));
        undoHandler.handle(undo(remote, Map.of("id", misskey.id(), "type", "Like", "actor", remote.uri().toString(),
                "object", post.uri().toString())));
        assertThat(reactions.summaries(List.of(post.id()), Optional.empty())).isEmpty();

        var late = activity(remote, "EmojiReact", post, Map.of("content", "🙃"), ago(10));
        undoHandler.handle(undo(remote, late.id()));
        reactHandler.handle(late);
        assertThat(reactions.summaries(List.of(post.id()), Optional.empty())).isEmpty();

        // Another actor on the same server can't undo it.
        var other = remote(remote.domain());
        var kept = activity(remote, "EmojiReact", post, Map.of("content", "😀"), ago(5));
        reactHandler.handle(kept);
        undoHandler.handle(undo(other, kept.id()));
        assertThat(reactions.summaries(List.of(post.id()), Optional.empty()).get(post.id())).hasSize(1);
    }

    @Test
    void sendsReactionsToRemoteStatusesAsEmojiReactsAndTheirUndos() throws Exception {
        var remote = remote();
        var post = remotePost(remote);
        var blob = emojis.addLocal(shortcode(), TestImages.png(32, 32), null);

        reactions.react(post.id(), bob.id(), reactions.choose(blob.shortcode()));
        settled();
        var saved = jdbc.sql("SELECT activity_uri FROM status_reaction WHERE status_id = :id")
                .param("id", post.id()).query(String.class).single();
        var body = json.readTree(outbound(saved));
        assertThat(body.path("type").asString()).isEqualTo("EmojiReact");
        assertThat(body.path("actor").asString()).isEqualTo(bob.uri().toString());
        assertThat(body.path("object").asString()).isEqualTo(post.uri().toString());
        assertThat(body.path("content").asString()).isEqualTo(":" + blob.shortcode() + ":");
        assertThat(body.path("tag").get(0).path("type").asString()).isEqualTo("Emoji");
        assertThat(body.path("tag").get(0).path("icon").path("url").asString())
                .startsWith("https://reactions.example/media/");
        assertThat(body.path("to").get(0).asString()).isEqualTo(remote.uri().toString());

        reactions.unreact(post.id(), bob.id(), reactions.choose(blob.shortcode()));
        settled();
        var undo = json.readTree(outbound(saved + "/undo"));
        assertThat(undo.path("type").asString()).isEqualTo("Undo");
        assertThat(undo.path("object").path("id").asString()).isEqualTo(saved);
        assertThat(undo.path("object").path("type").asString()).isEqualTo("EmojiReact");
        assertThat(undo.path("object").path("content").asString()).isEqualTo(":" + blob.shortcode() + ":");
    }

    /**
     * A URI rather than a template, which MockMvc would encode again.
     */
    private static URI path(Status status, String emoji) {
        return URI.create("/api/v1/pleroma/statuses/" + status.id() + "/reactions/" + encode(emoji));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {
        }).orElseThrow());
    }

    private Actor remote() {
        return remote("r" + UUID.randomUUID() + ".test");
    }

    private Actor remote(String host) {
        var uri = URI.create("https://" + host + "/users/u" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", host, URI.create(uri + "/inbox"), null, null,
                null));
    }

    private Status createPost() {
        return statuses.create(alice.id(), new NewStatus("a post", null, null, PUBLIC, false, null));
    }

    private Status remotePost(Actor author) {
        var id = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/posts/" + UUID.randomUUID()), null,
                author.id(), "<p>remote</p>", null, null, PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findVisible(id, Optional.empty()).orElseThrow();
    }

    private InboundActivity activity(Actor actor, String type, Status status, Map<String, Object> extra, Instant at) {
        var id = actor.uri() + "/activities/" + UUID.randomUUID();
        var body = new HashMap<String, Object>(extra);
        body.put("id", id);
        body.put("type", type);
        body.put("actor", actor.uri().toString());
        body.put("object", status.uri().toString());
        return new InboundActivity(id, actor.uri().toString(), type, json.writeValueAsString(body), at);
    }

    private InboundActivity undo(Actor actor, Object object) {
        var id = actor.uri() + "/activities/" + UUID.randomUUID();
        return new InboundActivity(id, actor.uri().toString(), "Undo", json.writeValueAsString(
                Map.of("id", id, "type", "Undo", "actor", actor.uri().toString(), "object", object)), Instant.now());
    }

    private static Instant ago(long seconds) {
        return Instant.now().minusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private RequestPostProcessor user(Actor actor) {
        return scoped(actor.id(), SCOPES);
    }

    private static RequestPostProcessor scoped(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600)));
    }

    private static String shortcode() {
        return "e" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String outbound(String id) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE activity_id = :id").param("id", id)
                .query(String.class).single();
    }

    private void settled() {
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.sql(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL").query(Long.class).single() == 0);
    }
}
