package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginSessionsIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String NEW_PASSWORD = "another long enough password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private AccountModeration moderation;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void resettingAPasswordEndsThatAccountsSessionsOnly() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var otherUsername = newUsername();
        var otherId = account(otherUsername);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var otherSession = TestOAuth.logIn(mockMvc, otherUsername, PASSWORD);
        assertLoggedIn(session);

        accounts.resetPassword(actorId, NEW_PASSWORD);

        assertThat(sessions(actorId)).isZero();
        assertLoggedOut(session);
        assertThat(sessions(otherId)).isOne();
        assertLoggedIn(otherSession);
        assertLoggedIn(TestOAuth.logIn(mockMvc, username, NEW_PASSWORD));
    }

    @Test
    void aLoginCheckingTheOldPasswordDuringAResetDoesNotSurviveIt() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        Mockito.doAnswer(invocation -> {
            var matches = invocation.callRealMethod();
            // Only the first check, because resetting doesn't check a password.
            Mockito.doCallRealMethod().when(passwordEncoder).matches(any(), any());
            accounts.resetPassword(actorId, NEW_PASSWORD);
            return matches;
        }).when(passwordEncoder).matches(any(), any());

        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        assertThat(sessions(actorId)).isOne();
        assertLoggedOut(session);
        assertThat(sessions(actorId)).isZero();
    }

    @Test
    void aLoginCheckingThePasswordDuringASuspensionDoesNotSurviveIt() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        Mockito.doAnswer(invocation -> {
            var matches = invocation.callRealMethod();
            Mockito.doCallRealMethod().when(passwordEncoder).matches(any(), any());
            moderation.suspend(actorId, Optional.empty());
            moderation.unsuspend(actorId);
            return matches;
        }).when(passwordEncoder).matches(any(), any());

        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        assertThat(sessions(actorId)).isOne();
        assertLoggedOut(session);
        assertThat(sessions(actorId)).isZero();
    }

    @Test
    void endingASuspensionDoesNotBringBackASession() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        moderation.suspend(actorId, Optional.empty());
        assertThat(sessions(actorId)).isZero();
        moderation.unsuspend(actorId);

        assertLoggedOut(session);
    }

    @Test
    void deletingAnAccountRemovesItsSessions() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        TestOAuth.logIn(mockMvc, username, PASSWORD);

        moderation.delete(actorId, Optional.empty());

        assertThat(sessions(actorId)).isZero();
    }

    @Test
    void changingARoleKeepsTheSession() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        accounts.setRole(actorId, AccountRole.ADMIN);
        accounts.setRole(actorId, AccountRole.USER);

        assertLoggedIn(session);
    }

    @Test
    void anUnreadableSessionCountsAsLoggedOut() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        var corrupted = jdbc.sql("""
                        UPDATE spring_session_attributes
                        SET attribute_bytes = :bytes
                        WHERE session_primary_id IN (SELECT primary_id FROM spring_session WHERE principal_name = :id)
                        """)
                .param("bytes", new byte[]{1, 2, 3})
                .param("id", actorId.toString())
                .update();
        assertThat(corrupted).isPositive();

        mockMvc.perform(get("/").session(session)).andExpect(status().isOk());
        assertLoggedOut(session);
    }

    @Test
    void aFailedLoginDoesNotStoreThePassword() throws Exception {
        var knownUsername = newUsername();
        account(knownUsername);

        for (var username : List.of(knownUsername, newUsername())) {
            var attempted = "attempted " + UUID.randomUUID();
            var session = new MockHttpSession();
            // A session exists by now in a browser, because the login form's CSRF token is kept in it.
            mockMvc.perform(get("/login").session(session)).andExpect(status().isOk());

            mockMvc.perform(post("/login").with(csrf()).session(session)
                            .param("username", username)
                            .param("password", attempted))
                    .andExpect(redirectedUrl("/login?error"));

            assertThat(jdbc.sql("""
                            SELECT count(*) FROM spring_session_attributes
                            WHERE position(:password IN attribute_bytes) > 0
                            """)
                    .param("password", attempted.getBytes(StandardCharsets.UTF_8))
                    .query(Long.class)
                    .single()).isZero();
        }
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM spring_session_attributes
                        WHERE attribute_name = 'SPRING_SECURITY_LAST_EXCEPTION'
                        """)
                .query(Long.class)
                .single()).isZero();
    }

    private void assertLoggedIn(MockHttpSession session) throws Exception {
        mockMvc.perform(accountPage().session(session)).andExpect(status().isOk());
    }

    private void assertLoggedOut(MockHttpSession session) throws Exception {
        mockMvc.perform(accountPage().session(session)).andExpect(redirectedUrl("/login"));
    }

    private static MockHttpServletRequestBuilder accountPage() {
        return get("/settings/delete-account").accept(MediaType.TEXT_HTML);
    }

    private long sessions(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = :id")
                .param("id", actorId.toString())
                .query(Long.class)
                .single();
    }

    private UUID account(String username) {
        accounts.createAccount(username, PASSWORD).orElseThrow();
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
    }
}
