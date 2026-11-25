package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.TokenPrincipal;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.RemoteStatuses;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static dev.biribiri.elysium.content.Visibility.DIRECT;
import static dev.biribiri.elysium.content.Visibility.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.opaqueToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class ThreadMutesIntegrationTests {
    private static Instant started;

    private static final Set<String> SCOPES = Set.of("read:statuses", "read:notifications", "read:conversations",
            "write:mutes");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private RemoteStatuses remoteStatuses;
    @Autowired
    private LikeService likes;
    @Autowired
    private BoostService boosts;
    @Autowired
    private JdbcClient jdbc;
    private Actor alice;
    private Actor bob;
    private Actor carol;

    @BeforeEach
    void setUp() {
        settled();
        jdbc.sql("DELETE FROM notification").update();
        jdbc.sql("DELETE FROM conversation").update();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM status_thread_alias").update();
        jdbc.sql("DELETE FROM status_thread_mute").update();
        alice = local("tmalice");
        bob = local("tmbob");
        carol = local("tmcarol");
    }

    @Test
    void mutesAndUnmutesTheThreadForTheUserOnly() throws Exception {
        var root = create(bob, "root", PUBLIC, null);
        var reply = create(carol, "reply", PUBLIC, root.id());

        mute(alice, reply.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reply.id().toString()))
                .andExpect(jsonPath("$.muted").value(true));
        mute(alice, reply.id()).andExpect(status().isOk()).andExpect(jsonPath("$.muted").value(true));
        view(alice, root.id()).andExpect(jsonPath("$.muted").value(true));
        view(bob, root.id()).andExpect(jsonPath("$.muted").value(false));

        unmute(alice, root.id()).andExpect(status().isOk()).andExpect(jsonPath("$.muted").value(false));
        unmute(alice, root.id()).andExpect(status().isOk()).andExpect(jsonPath("$.muted").value(false));
        view(alice, reply.id()).andExpect(jsonPath("$.muted").value(false));
    }

    @Test
    void aBoostsIdMutesTheThreadOfWhatItBoosts() throws Exception {
        var root = create(bob, "root", PUBLIC, null);
        var boost = boosts.reblog(root.id(), carol.id(), PUBLIC).orElseThrow();

        mute(alice, boost.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(root.id().toString()))
                .andExpect(jsonPath("$.muted").value(true));
        // The boost itself isn't part of the thread, as on Mastodon.
        view(alice, boost.id()).andExpect(jsonPath("$.muted").value(false))
                .andExpect(jsonPath("$.reblog.muted").value(true));
        unmute(alice, boost.id()).andExpect(status().isOk()).andExpect(jsonPath("$.muted").value(false));
    }

    @Test
    void refusesWhatTheUserCantSeeAndNeedsTheScopeAndAUser() throws Exception {
        var direct = create(bob, "secret", DIRECT, null);
        var visible = create(bob, "hello", PUBLIC, null);

        mute(alice, direct.id()).andExpect(status().isNotFound());
        mute(alice, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/statuses/nope/mute").with(user(alice))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/statuses/" + visible.id() + "/mute").with(token(alice.id(), Set.of("read"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/statuses/" + visible.id() + "/mute").with(token(null, SCOPES)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(muteCount()).isZero();
    }

    @Test
    void aMutedThreadAddsNoNotificationsUntilItsUnmuted() throws Exception {
        var root = create(alice, "root", PUBLIC, null);
        mute(alice, root.id()).andExpect(status().isOk());

        create(bob, "@tmalice muted", PUBLIC, root.id());
        likes.favourite(root.id(), carol.id());
        // Someone else in the thread is still told.
        create(carol, "@tmbob still here", PUBLIC, root.id());
        create(bob, "@tmalice elsewhere", PUBLIC, null);
        settled();
        notifications(alice).andExpect(jsonPath("$", hasSize(1)));
        notifications(bob).andExpect(jsonPath("$", hasSize(1)));

        unmute(alice, root.id()).andExpect(status().isOk());
        var later = create(bob, "@tmalice back", PUBLIC, root.id());
        settled();
        notifications(alice).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status.id").value(later.id().toString()));
    }

    @Test
    void directStatusesStillReachConversations() throws Exception {
        var root = create(bob, "@tmalice hi", DIRECT, null);
        settled();
        mute(alice, root.id()).andExpect(status().isOk());

        var reply = create(bob, "@tmalice again", DIRECT, root.id());
        settled();
        notifications(alice).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/v1/conversations").with(user(alice))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].last_status.id").value(reply.id().toString()));
    }

    /**
     * A part of a thread whose parent is missing is muted, and the parent then arrives as a reply in another thread.
     */
    @Test
    void aMuteFollowsItsThreadWhenThreadsJoin() throws Exception {
        var root = create(alice, "root", PUBLIC, null);
        var remote = remote("thread-mutes-remote.example");
        var missing = note(remote);
        var orphan = store(remote, note(remote), missing, Map.of(alice.id(), false));
        settled();
        mute(alice, orphan).andExpect(status().isOk());

        store(remote, missing, root.uri(), Map.of(alice.id(), false));
        create(bob, "@tmalice joined", PUBLIC, root.id());
        settled();
        view(alice, root.id()).andExpect(jsonPath("$.muted").value(true));
        view(alice, orphan).andExpect(jsonPath("$.muted").value(true));
        notifications(alice).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status.id").value(orphan.toString()));
        assertThat(muteCount()).isOne();
    }

    @Test
    void mutingBothPartsOfAThreadLeavesOneMuteWhenTheyJoin() throws Exception {
        var root = create(alice, "root", PUBLIC, null);
        var remote = remote("thread-mutes-both.example");
        var missing = note(remote);
        var orphan = store(remote, note(remote), missing, Map.of());
        mute(alice, root.id()).andExpect(status().isOk());
        mute(alice, orphan).andExpect(status().isOk());
        assertThat(muteCount()).isEqualTo(2);

        store(remote, missing, root.uri(), Map.of());
        assertThat(muteCount()).isOne();
        unmute(alice, orphan).andExpect(status().isOk());
        view(alice, root.id()).andExpect(jsonPath("$.muted").value(false));
    }

    private ResultActions mute(Actor actor, UUID id) throws Exception {
        return mvc.perform(post("/api/v1/statuses/" + id + "/mute").with(user(actor)));
    }

    private ResultActions unmute(Actor actor, UUID id) throws Exception {
        return mvc.perform(post("/api/v1/statuses/" + id + "/unmute").with(user(actor)));
    }

    private ResultActions view(Actor actor, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/statuses/" + id).with(user(actor))).andExpect(status().isOk());
    }

    private ResultActions notifications(Actor actor) throws Exception {
        return mvc.perform(get("/api/v1/notifications").with(user(actor))).andExpect(status().isOk());
    }

    private long muteCount() {
        return jdbc.sql("SELECT count(*) FROM status_thread_mute").query(Long.class).single();
    }

    private Status create(Actor author, String text, Visibility visibility, UUID parent) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, parent));
    }

    private UUID store(Actor remote, URI uri, URI parent, Map<UUID, Boolean> mentions) {
        return remoteStatuses.storeRemote(new RemoteStatus(uri, null, remote.id(), "<p>message</p>", null, null,
                PUBLIC, false, parent, Instant.now(), null, mentions)).orElseThrow();
    }

    private Actor local(String name) {
        return actors.findLocalByUsername(name).orElseGet(() -> actors.createLocalIfAbsent(name, actor -> {}).orElseThrow());
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(uri, "remote", domain, URI.create(uri + "/inbox"), null, null, null));
    }

    private static URI note(Actor actor) {
        return URI.create(actor.uri() + "/notes/" + UUID.randomUUID());
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
