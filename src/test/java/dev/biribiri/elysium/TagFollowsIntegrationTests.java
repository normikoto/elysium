package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorDataRemoval;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.graph.MuteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class TagFollowsIntegrationTests {
    private static Instant started;


    private static final Set<String> SCOPES = Set.of("read:follows", "write:follows", "read:statuses", "read:search");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private BoostService boosts;
    @Autowired
    private BlockService blocks;
    @Autowired
    private MuteService mutes;
    @Autowired
    private List<ActorDataRemoval> removals;
    @Autowired
    private JdbcClient jdbc;
    private Actor alice;
    private Actor bob;
    private Actor carol;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM tag_follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        jdbc.sql("DELETE FROM account_mute").update();
        alice = local("tfalice");
        bob = local("tfbob");
        carol = local("tfcarol");
    }

    @Test
    void followsAndUnfollowsATag() throws Exception {
        tag(alice, "Cats", "follow").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("cats"))
                .andExpect(jsonPath("$.url").value("https://elysium.example/tags/cats"))
                .andExpect(jsonPath("$.following").value(true));
        tag(alice, "cats", "follow").andExpect(status().isOk()).andExpect(jsonPath("$.following").value(true));

        mvc.perform(get("/api/v1/tags/cats").with(user(alice))).andExpect(jsonPath("$.following").value(true));
        mvc.perform(get("/api/v1/tags/cats").with(user(bob))).andExpect(jsonPath("$.following").value(false));
        mvc.perform(get("/api/v1/tags/cats")).andExpect(jsonPath("$.following").doesNotExist());
        mvc.perform(get("/api/v2/search").param("q", "#cats").param("type", "hashtags").with(user(alice)))
                .andExpect(jsonPath("$.hashtags[0].name").value("cats"))
                .andExpect(jsonPath("$.hashtags[0].following").value(true));

        tag(alice, "cats", "unfollow").andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        tag(alice, "cats", "unfollow").andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
        mvc.perform(get("/api/v1/followed_tags").with(user(alice))).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anInvalidNameIsNotFound() throws Exception {
        tag(alice, "123", "follow").andExpect(status().isNotFound());
        tag(alice, "123", "unfollow").andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM tag_follow").query(Long.class).single()).isZero();
    }

    @Test
    void needsScopesAndAUser() throws Exception {
        mvc.perform(post("/api/v1/tags/cats/follow")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/followed_tags")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tags/cats/follow").with(token(alice.id(), Set.of("read:follows"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tags/cats/unfollow").with(token(alice.id(), Set.of("read:follows"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/followed_tags").with(token(alice.id(), Set.of("write:follows"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/tags/cats/follow").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/followed_tags").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listsFollowedTagsNewestFirst() throws Exception {
        for (var name : List.of("cats", "dogs", "birds")) {
            tag(alice, name, "follow").andExpect(status().isOk());
        }
        tag(bob, "fish", "follow").andExpect(status().isOk());

        var response = mvc.perform(get("/api/v1/followed_tags").param("limit", "2").with(user(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("birds", "dogs")))
                .andExpect(jsonPath("$[0].following").value(true))
                .andExpect(jsonPath("$[0].history", hasSize(7)))
                .andExpect(header().string("Link", containsString("rel=\"next\"")))
                .andReturn().getResponse();
        var next = response.getHeader("Link")
                .replaceAll(".*<https://elysium.example([^>]*)>; rel=\"next\".*", "$1");
        var prev = response.getHeader("Link")
                .replaceAll(".*<https://elysium.example([^>]*)>; rel=\"prev\".*", "$1");

        mvc.perform(get(next).with(user(alice)))
                .andExpect(jsonPath("$[*].name").value(contains("cats")));
        mvc.perform(get(prev).with(user(alice))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/v1/followed_tags").param("max_id", "nope").with(user(alice)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void addsPublicStatusesWithTheTagToTheHomeTimeline() throws Exception {
        var before = publish(bob, "Older #cats", Visibility.PUBLIC, null);
        tag(alice, "cats", "follow").andExpect(status().isOk());
        var parent = publish(carol, "Hello", Visibility.PUBLIC, null);
        var reply = publish(bob, "@tfcarol #Cats reply", Visibility.PUBLIC, parent.id());
        var remote = remote("cats");
        publish(bob, "#cats quietly", Visibility.UNLISTED, null);
        publish(bob, "#cats for followers", Visibility.PRIVATE, null);
        publish(bob, "@tfalice #cats psst", Visibility.DIRECT, null);
        publish(bob, "#dogs", Visibility.PUBLIC, null);
        var boosted = publish(carol, "Not tagged", Visibility.PUBLIC, null);
        boosts.reblog(before.id(), carol.id(), Visibility.PUBLIC).orElseThrow();
        boosts.reblog(boosted.id(), bob.id(), Visibility.PUBLIC).orElseThrow();
        settled();

        expectHome(alice, remote, reply.id().toString(), before.id().toString());

        tag(alice, "cats", "unfollow").andExpect(status().isOk());
        expectHome(alice);
    }

    @Test
    void leavesOutAccountsTheUserBlockedOrMuted() throws Exception {
        tag(alice, "cats", "follow").andExpect(status().isOk());
        var shown = publish(carol, "#cats", Visibility.PUBLIC, null);
        publish(bob, "#cats blocked", Visibility.PUBLIC, null);
        blocks.block(alice, bob);
        var dave = local("tfdave");
        publish(dave, "#cats muted", Visibility.PUBLIC, null);
        mutes.mute(alice.id(), dave.id(), true, Optional.empty());
        settled();

        expectHome(alice, shown.id().toString());
    }

    @Test
    void anAccountsTagFollowsGoWhenItsDeleted() throws Exception {
        tag(alice, "cats", "follow").andExpect(status().isOk());
        tag(bob, "cats", "follow").andExpect(status().isOk());

        removals.forEach(removal -> removal.removeForDeleted(alice.id()));

        assertThat(jdbc.sql("SELECT account_id FROM tag_follow").query(UUID.class).list()).containsExactly(bob.id());
    }

    private void expectHome(Actor viewer, String... ids) throws Exception {
        var body = mvc.perform(get("/api/v1/timelines/home").with(user(viewer)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].id")).containsExactly(ids);
    }

    private ResultActions tag(Actor actor, String name, String action) throws Exception {
        return mvc.perform(post("/api/v1/tags/" + name + "/" + action).with(user(actor)));
    }

    private Status publish(Actor author, String text, Visibility visibility, UUID inReplyTo) {
        var status = statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, inReplyTo));
        settled();
        return status;
    }

    /**
     * Stores a public status with the tag from a new actor on another server.
     */
    private String remote(String tag) {
        var host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        var author = actors.upsertRemote(
                new RemoteActorProfile(uri, "s", host, URI.create(uri + "/inbox"), null, null, null, null));
        return remoteStatuses.storeRemote(new RemoteStatus(
                        URI.create(author.uri() + "/notes/" + UUID.randomUUID()), null, author.id(), "<p>From afar</p>",
                        null, null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), Set.of(tag)))
                .orElseThrow().toString();
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private static RequestPostProcessor user(Actor actor) {
        return token(actor.id(), SCOPES);
    }

    private static RequestPostProcessor token(UUID actor, Set<String> scopes) {
        return opaqueToken().principal(new TokenPrincipal("test", Optional.ofNullable(actor), scopes,
                Instant.now(), Instant.now().plusSeconds(3600), "test"));
    }

    @BeforeAll
    static void rememberTheStart() {
        started = Instant.now();
    }

    private void settled() {
        TestEvents.awaitHandled(jdbc, started);
    }
}
