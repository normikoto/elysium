package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.ProfileChange;
import dev.biribiri.elysium.actor.ProfileService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.actor.RemoteFollowPages;
import dev.biribiri.elysium.graph.FollowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Following from profile pages, and from {@code /authorize_interaction}, where other servers' remote follow sends users.
 * No job worker runs, so nothing is delivered.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://follow-pages.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class FollowPagesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * Logging in is slow because of Argon2, so each account only logs in once.
     */
    private static final Map<String, MockHttpSession> SESSIONS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private ProfileService profiles;
    @Autowired
    private FollowService follows;
    @MockitoBean
    private RemoteFollowPages followPages;
    @MockitoBean
    private RemoteActorResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM account_block").update();
        session("alice");
        session("bob");
        profiles.update(account("bob").id(), ProfileChange.NONE.withManuallyApprovesFollowers(false));
    }

    @Test
    void anAnonymousVisitorIsAskedForTheirAddress() throws Exception {
        mockMvc.perform(get("/users/bob"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/users/bob/remote-follow\"")))
                .andExpect(content().string(containsString(
                        "/authorize_interaction?uri=acct:bob@follow-pages.example")))
                .andExpect(content().string(not(containsString("action=\"/follow\""))));
    }

    @Test
    void aLoggedInUserFollowsAndUnfollows() throws Exception {
        var alice = session("alice");
        var bob = account("bob");
        mockMvc.perform(get("/users/bob").session(alice))
                .andExpect(content().string(containsString("action=\"/follow\"")));

        mockMvc.perform(post("/follow").session(alice).with(csrf()).param("account", bob.id().toString()))
                .andExpect(redirectedUrl("/users/bob"));
        assertThat(follows.isFollowing(account("alice").id(), bob.id())).isTrue();
        mockMvc.perform(get("/users/bob").session(alice))
                .andExpect(content().string(containsString("Unfollow")));

        mockMvc.perform(post("/unfollow").session(alice).with(csrf()).param("account", bob.id().toString()))
                .andExpect(redirectedUrl("/users/bob"));
        assertThat(follows.isFollowing(account("alice").id(), bob.id())).isFalse();
    }

    @Test
    void aRequestToALockedAccountCanBeWithdrawn() throws Exception {
        var alice = session("alice");
        var bob = account("bob");
        profiles.update(bob.id(), ProfileChange.NONE.withManuallyApprovesFollowers(true));

        mockMvc.perform(post("/follow").session(alice).with(csrf()).param("account", bob.id().toString()))
                .andExpect(redirectedUrl("/users/bob"));
        mockMvc.perform(get("/users/bob").session(alice))
                .andExpect(content().string(containsString("Withdraw request")));

        mockMvc.perform(post("/unfollow").session(alice).with(csrf()).param("account", bob.id().toString()));
        mockMvc.perform(get("/users/bob").session(alice))
                .andExpect(content().string(containsString("action=\"/follow\"")));
    }

    @Test
    void aUserCantFollowThemselves() throws Exception {
        var alice = session("alice");
        mockMvc.perform(get("/users/alice").session(alice))
                .andExpect(content().string(not(containsString("action=\"/follow\""))));
        mockMvc.perform(post("/follow").session(alice).with(csrf()).param("account", account("alice").id().toString()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void followingNeedsALoginAndACsrfToken() throws Exception {
        var bob = account("bob").id().toString();
        mockMvc.perform(post("/follow").with(csrf()).param("account", bob)).andExpect(status().isForbidden());
        mockMvc.perform(post("/follow").session(session("alice")).param("account", bob)).andExpect(status().isForbidden());
        assertThat(follows.isFollowing(account("alice").id(), account("bob").id())).isFalse();
    }

    @Test
    void aVisitorIsSentToTheirOwnServer() throws Exception {
        var page = URI.create("https://remote.example/authorize_interaction?uri=https%3A%2F%2Ffollow-pages.example%2Fusers%2Fbob");
        when(followPages.find("carol", "remote.example", account("bob").uri())).thenReturn(Optional.of(page));

        mockMvc.perform(post("/users/bob/remote-follow").with(csrf()).param("address", " @carol@Remote.Example "))
                .andExpect(redirectedUrl(page.toString()));
    }

    @Test
    void aVisitorWhoseServerCantBeAskedIsToldToSearchInsteadAndKeepsTheirAddress() throws Exception {
        when(followPages.find(anyString(), anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/users/bob/remote-follow").with(csrf()).param("address", "carol@remote.example"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("value=\"carol@remote.example\"")))
                .andExpect(content().string(containsString("@bob@follow-pages.example")));
        mockMvc.perform(post("/users/bob/remote-follow").with(csrf()).param("address", "carol"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAddressHereGoesThroughLogin() throws Exception {
        mockMvc.perform(post("/users/bob/remote-follow").with(csrf()).param("address", "alice@follow-pages.example"))
                .andExpect(redirectedUrl("/authorize_interaction?uri=acct:bob@follow-pages.example"));
        verify(followPages, never()).find(any(), any(), any());

        mockMvc.perform(get("/authorize_interaction").param("uri", "acct:bob@follow-pages.example"))
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(get("/authorize_interaction").param("uri", "acct:bob@follow-pages.example")
                        .session(session("alice")))
                .andExpect(redirectedUrl("/users/bob"));
        mockMvc.perform(get("/authorize_interaction").param("uri", "https://follow-pages.example/users/bob")
                        .session(session("alice")))
                .andExpect(redirectedUrl("/users/bob"));
    }

    @Test
    void aUserFollowsAnAccountOnAnotherServerThatSentThemHere() throws Exception {
        var alice = session("alice");
        var dave = remote("remote.example");
        when(resolver.resolveAcct(any(), eq("dave"), eq("remote.example"))).thenReturn(Optional.of(dave));
        when(resolver.findStored("dave", "remote.example")).thenReturn(Optional.of(dave));

        mockMvc.perform(get("/authorize_interaction").param("uri", "acct:dave@remote.example").session(alice))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>@dave@remote.example</h1>")))
                .andExpect(content().string(not(containsString("<p>@dave@remote.example</p>"))))
                .andExpect(content().string(containsString("View on their server")))
                .andExpect(content().string(containsString("action=\"/follow\"")));

        mockMvc.perform(post("/follow").session(alice).with(csrf()).param("account", dave.id().toString()))
                .andExpect(redirectedUrl("/authorize_interaction?uri=" + dave.uri()));
        assertThat(follows.relationships(account("alice").id(), List.of(dave.id())).getFirst().requested())
                .isTrue();
    }

    @Test
    void anAccountOnABlockedServerCantBeFollowed() throws Exception {
        var dave = remote("blocked.example");
        when(resolver.findStored(anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/follow").session(session("alice")).with(csrf()).param("account", dave.id().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAccountThatCantBeFoundSaysSo() throws Exception {
        when(resolver.resolveAcct(any(), anyString(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/authorize_interaction").param("uri", "acct:nobody@remote.example")
                        .session(session("alice")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("be found")));
    }

    private Actor remote(String domain) {
        var uri = URI.create("https://" + domain + "/users/" + UUID.randomUUID());
        return actors.upsertRemote(new RemoteActorProfile(
                uri, "dave", domain, URI.create(uri + "/inbox"), null, URI.create("https://" + domain + "/inbox"), null,
                null));
    }

    private Actor account(String username) {
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private MockHttpSession session(String username) throws Exception {
        var cached = SESSIONS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        SESSIONS.put(username, session);
        return session;
    }
}
