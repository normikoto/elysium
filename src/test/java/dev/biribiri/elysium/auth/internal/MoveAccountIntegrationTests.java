package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.activitypub.outbound.Persons;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The alias and move pages, and what moving a local account sends and changes. The real scheduler runs the job that moves
 * local followers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://moving.example",
        "db-scheduler.polling-interval=PT0.1S",
        "db-scheduler.heartbeat-interval=PT1S"})
@AutoConfigureMockMvc
class MoveAccountIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private LocalAccounts accounts;
    @Autowired
    private FollowService follows;
    @Autowired
    private Persons persons;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JsonMapper json;
    @MockitoBean
    private RemoteActorResolver resolver;

    @Test
    void needsALogin() throws Exception {
        mockMvc.perform(get("/settings/move").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/settings/aliases").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/settings/move").with(csrf()).param("address", "a").param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/settings/aliases").with(csrf()).param("address", "a")).andExpect(status().isForbidden());
    }

    @Test
    void theSettingsPageLinksToThem() throws Exception {
        var alice = account();
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("href=\"/settings/aliases\"")))
                .andExpect(content().string(containsString("href=\"/settings/move\"")));
        mockMvc.perform(get("/settings/move").session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/settings/aliases").session(session)).andExpect(status().isOk());
    }

    @Test
    void anAliasIsServedInTheActorDocumentUntilItsRemoved() throws Exception {
        var alice = account();
        var bob = account();
        var session = TestOAuth.logIn(mockMvc, bob.username(), PASSWORD);

        mockMvc.perform(post("/settings/aliases").with(csrf()).session(session).param("address", "@" + alice.username()))
                .andExpect(redirectedUrl("/settings/aliases"));

        var document = json.readTree(json.writeValueAsString(persons.of(bob)));
        assertThat(document.path("alsoKnownAs").get(0).stringValue()).isEqualTo(alice.uri().toString());
        assertThat(json.writeValueAsString(document.path("@context"))).contains("as:alsoKnownAs").contains("as:movedTo");
        mockMvc.perform(get("/settings/aliases").session(session))
                .andExpect(content().string(containsString(alice.uri().toString())));

        mockMvc.perform(post("/settings/aliases/remove").with(csrf()).session(session).param("uri", alice.uri().toString()))
                .andExpect(redirectedUrl("/settings/aliases"));
        assertThat(profiles.find(bob.id()).alsoKnownAs()).isEmpty();
        assertThat(json.readTree(json.writeValueAsString(persons.of(bob))).has("alsoKnownAs")).isFalse();
    }

    @Test
    void anAccountCantBeItsOwnAlias() throws Exception {
        var alice = account();
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);

        mockMvc.perform(post("/settings/aliases").with(csrf()).session(session).param("address", alice.username()))
                .andExpect(status().isUnprocessableContent());
        assertThat(profiles.find(alice.id()).alsoKnownAs()).isEmpty();
    }

    @Test
    void aWrongPasswordMovesNothing() throws Exception {
        var alice = account();
        var bob = account();
        alias(bob, alice);
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);

        move(session, bob.username(), "wrong password")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Incorrect password.")));
        assertThat(profiles.find(alice.id()).movedTo()).isNull();
    }

    @Test
    void theNewAccountHasToListTheOldOne() throws Exception {
        var alice = account();
        var bob = account();
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);

        move(session, bob.username(), PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("alias")));
        assertThat(profiles.find(alice.id()).movedTo()).isNull();
    }

    @Test
    void movingToALocalAccountMovesLocalFollowersAndTellsOtherServers() throws Exception {
        var alice = account();
        var bob = account();
        var carol = account();
        follows.follow(carol, alice);
        var remoteFollower = remote(List.of());
        jdbc.sql("""
                        INSERT INTO follow (id, follower_id, followed_id, state, activity_uri)
                        VALUES (gen_random_uuid(), :f, :t, 'accepted', :activity)
                        """)
                .param("f", remoteFollower.id()).param("t", alice.id())
                .param("activity", remoteFollower.uri() + "#follow").update();
        alias(bob, alice);
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);

        move(session, "@" + bob.username() + "@moving.example", PASSWORD).andExpect(redirectedUrl("/settings/move"));

        assertThat(profiles.find(alice.id()).movedTo()).isEqualTo(bob.uri());
        assertThat(json.readTree(json.writeValueAsString(persons.of(alice))).path("movedTo").stringValue())
                .isEqualTo(bob.uri().toString());
        await().atMost(WAIT).until(() -> follows.isFollowing(carol.id(), bob.id()));
        assertThat(follows.isFollowing(carol.id(), alice.id())).isFalse();
        await().atMost(WAIT).until(() -> outbound(alice.id()).stream().anyMatch(body -> body.contains("\"Move\"")));
        var move = json.readTree(outbound(alice.id()).stream().filter(body -> body.contains("\"Move\"")).findFirst()
                .orElseThrow());
        assertThat(move.path("id").stringValue()).startsWith(alice.uri() + "#moves/");
        assertThat(move.path("actor").stringValue()).isEqualTo(alice.uri().toString());
        assertThat(move.path("object").stringValue()).isEqualTo(alice.uri().toString());
        assertThat(move.path("target").stringValue()).isEqualTo(bob.uri().toString());
        assertThat(outbound(alice.id())).anyMatch(body -> body.contains("\"Update\"") && body.contains("#moved/"));
        mockMvc.perform(get("/settings/move").session(session))
                .andExpect(content().string(containsString(bob.uri().toString())));
    }

    @Test
    void theApiShowsTheMoveAndNotifiesMovedFollowers() throws Exception {
        var alice = account();
        var bob = account();
        var carol = account();
        follows.follow(carol, alice);
        alias(bob, alice);
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);
        var token = TestOAuth.userToken(mockMvc, carol.username(), PASSWORD, "read");

        mockMvc.perform(get("/api/v1/accounts/" + alice.id()).header("Authorization", "Bearer " + token))
                .andExpect(content().string(not(containsString("\"moved\""))));
        move(session, bob.username(), PASSWORD).andExpect(redirectedUrl("/settings/move"));

        var account = json.readTree(mockMvc.perform(get("/api/v1/accounts/" + alice.id())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(account.path("moved").path("id").stringValue()).isEqualTo(bob.id().toString());
        assertThat(account.path("moved").has("moved")).isFalse();
        await().atMost(WAIT).until(() -> json.readTree(mockMvc.perform(get("/api/v1/notifications")
                        .param("types[]", "move")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).size() == 1);
        var notification = json.readTree(mockMvc.perform(get("/api/v1/notifications")
                        .param("types[]", "move")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get(0);
        assertThat(notification.path("type").stringValue()).isEqualTo("move");
        assertThat(notification.path("account").path("id").stringValue()).isEqualTo(alice.id().toString());
        assertThat(notification.path("target").path("id").stringValue()).isEqualTo(bob.id().toString());
    }

    @Test
    void movingToAnAccountOnAnotherServerFetchesItAgain() throws Exception {
        var alice = account();
        var target = remote(List.of(alice.uri()));
        when(resolver.resolveAcctFresh(any(), eq(target.username()), eq(target.domain()))).thenReturn(Optional.of(target));
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);

        move(session, "@" + target.username() + "@" + target.domain(), PASSWORD).andExpect(redirectedUrl("/settings/move"));

        assertThat(profiles.find(alice.id()).movedTo()).isEqualTo(target.uri());
    }

    @Test
    void anAccountCanOnlyMoveOnceEveryThirtyDays() throws Exception {
        var alice = account();
        var bob = account();
        var dave = account();
        alias(bob, alice);
        alias(dave, alice);
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);
        move(session, bob.username(), PASSWORD).andExpect(redirectedUrl("/settings/move"));

        move(session, dave.username(), PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("once every 30 days")));
        assertThat(profiles.find(alice.id()).movedTo()).isEqualTo(bob.uri());
    }

    @Test
    void stoppingBeingMovedSendsAnUpdateWithoutMovedTo() throws Exception {
        var alice = account();
        var bob = account();
        alias(bob, alice);
        var session = TestOAuth.logIn(mockMvc, alice.username(), PASSWORD);
        move(session, bob.username(), PASSWORD).andExpect(redirectedUrl("/settings/move"));

        mockMvc.perform(post("/settings/move/cancel").with(csrf()).session(session))
                .andExpect(redirectedUrl("/settings/move"));

        assertThat(profiles.find(alice.id()).movedTo()).isNull();
        assertThat(json.readTree(json.writeValueAsString(persons.of(alice))).has("movedTo")).isFalse();
    }

    private ResultActions move(MockHttpSession session, String address, String password) throws Exception {
        return mockMvc.perform(post("/settings/move").with(csrf()).session(session)
                .param("address", address)
                .param("password", password));
    }

    private void alias(Actor account, Actor alias) throws Exception {
        var session = TestOAuth.logIn(mockMvc, account.username(), PASSWORD);
        mockMvc.perform(post("/settings/aliases").with(csrf()).session(session).param("address", alias.username()))
                .andExpect(redirectedUrl("/settings/aliases"));
    }

    private Actor account() {
        var username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        accounts.createAccount(username, PASSWORD).orElseThrow();
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private Actor remote(List<URI> alsoKnownAs) {
        var domain = "r" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + domain + "/users/bob");
        var profile = new RemoteProfile("", "", "", null, null, List.of(), false, false, false, false, null, null,
                alsoKnownAs, null);
        return actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null,
                null, null, null, profile));
    }

    private List<String> outbound(UUID actorId) {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id = :id ORDER BY created_at")
                .param("id", actorId).query(String.class).list();
    }
}
