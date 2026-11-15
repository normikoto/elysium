package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String NEW_PASSWORD = "another long enough password";
    private static final String PAGE = "/settings/password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Test
    void needsALogin() throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post(PAGE).with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmation", NEW_PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSettingsPageLinksToIt() throws Exception {
        var username = newUsername();
        account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("href=\"/settings/password\"")));
        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordChangesNothing() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        change(session, "wrong password", NEW_PASSWORD, NEW_PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("Incorrect password.")));

        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isTrue();
        assertLoggedIn(session);
    }

    @Test
    void newPasswordsThatDoNotMatchChangeNothing() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        change(session, PASSWORD, NEW_PASSWORD, NEW_PASSWORD + "!")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("match")));

        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isTrue();
    }

    @Test
    void aTooShortNewPasswordChangesNothing() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        change(session, PASSWORD, "short", "short")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("8 to 128 characters")));

        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isTrue();
    }

    @Test
    void needsACsrfToken() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        mockMvc.perform(post(PAGE).session(session)
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmation", NEW_PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isTrue();
    }

    @Test
    void changingItEndsOtherSessionsAndAppsButKeepsThisOne() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var other = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var token = TestOAuth.userToken(mockMvc, TestOAuth.registerApp(mockMvc, "read"), other, "read");
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        var result = change(session, PASSWORD, NEW_PASSWORD, NEW_PASSWORD)
                .andExpect(redirectedUrl(PAGE + "?changed"))
                .andReturn();

        assertThat(accounts.passwordMatches(actorId, NEW_PASSWORD)).isTrue();
        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isFalse();
        assertLoggedOut(other);
        mockMvc.perform(get("/api/v1/accounts/verify_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        var newSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(newSession).isNotNull();
        mockMvc.perform(get(PAGE).param("changed", "").session(newSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your password is changed")));
        TestOAuth.logIn(mockMvc, username, NEW_PASSWORD);
    }

    @Test
    void changingFromAnEndedGenerationChangesNothing() {
        var username = newUsername();
        var actorId = account(username);
        var generation = accounts.loginGeneration(actorId).orElseThrow();
        accounts.resetPassword(actorId, PASSWORD);

        assertThat(accounts.changePassword(actorId, NEW_PASSWORD, generation)).isEmpty();
        assertThat(accounts.passwordMatches(actorId, PASSWORD)).isTrue();
    }

    private ResultActions change(MockHttpSession session, String current, String newPassword, String confirmation)
            throws Exception {
        return mockMvc.perform(post(PAGE).with(csrf()).session(session)
                .param("currentPassword", current)
                .param("newPassword", newPassword)
                .param("confirmation", confirmation));
    }

    private void assertLoggedIn(MockHttpSession session) throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML).session(session)).andExpect(status().isOk());
    }

    private void assertLoggedOut(MockHttpSession session) throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML).session(session)).andExpect(redirectedUrl("/login"));
    }

    private UUID account(String username) {
        accounts.createAccount(username, PASSWORD).orElseThrow();
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
    }
}
