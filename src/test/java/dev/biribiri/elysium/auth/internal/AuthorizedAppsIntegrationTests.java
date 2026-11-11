package dev.biribiri.elysium.auth.internal;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizedAppsIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String PAGE = "/oauth/authorized_applications";
    private static final String USER = "appsowner";
    private static final String OTHER_USER = "appsother";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockHttpSession session;

    @BeforeEach
    void logIn() throws Exception {
        for (var username : List.of(USER, OTHER_USER)) {
            if (actors.findLocalByUsername(username).isEmpty()) {
                var hash = passwordEncoder.encode(PASSWORD);
                actors.createLocalIfAbsent(username, actor -> jdbc.sql("""
                                INSERT INTO account_credential (actor_id, password_hash) VALUES (:actorId, :hash)
                                """)
                        .param("actorId", actor.id())
                        .param("hash", hash)
                        .update());
            }
        }
        session = TestOAuth.logIn(mockMvc, USER, PASSWORD);
    }

    @Test
    void listsAnApprovedApp() throws Exception {
        var name = uniqueName();
        var app = register(name, "https://app.example/home", "read write");
        TestOAuth.userToken(mockMvc, app, session, "read");

        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)))
                .andExpect(content().string(containsString("href=\"https://app.example/home\"")))
                .andExpect(content().string(containsString("Can use: <span>read</span>")))
                .andExpect(content().string(containsString("<time")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void escapesAppNames() throws Exception {
        var app = register("<script>alert(1)</script>", null, "read");
        TestOAuth.userToken(mockMvc, app, session, "read");

        mockMvc.perform(get(PAGE).session(session))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")));
    }

    @Test
    void leavesOutAnAppWhoseConsentPageWasOnlyOpened() throws Exception {
        var name = uniqueName();
        var app = register(name, null, "read");

        assertThat(TestOAuth.authorize(mockMvc, app, session, "read")).contains("/oauth/consent");

        mockMvc.perform(get(PAGE).session(session)).andExpect(content().string(not(containsString(name))));
    }

    @Test
    void listsAnAppThatOnlyHasAConsentLeft() throws Exception {
        var name = uniqueName();
        var app = register(name, null, "read");
        var token = TestOAuth.userToken(mockMvc, app, session, "read");

        mockMvc.perform(post("/oauth/revoke")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret"))
                        .param("token", token))
                .andExpect(status().isOk());

        mockMvc.perform(get(PAGE).session(session)).andExpect(content().string(containsString(name)));
    }

    @Test
    void anAppWhoseConsentWasDeniedLaterIsStillListedAndCanBeRevoked() throws Exception {
        var name = uniqueName();
        var app = register(name, null, "read write");
        var token = TestOAuth.userToken(mockMvc, app, session, "read");
        TestOAuth.deny(mockMvc, app, session, TestOAuth.authorize(mockMvc, app, session, "read write"));

        assertThat(consents(app)).isZero();
        mockMvc.perform(get(PAGE).session(session)).andExpect(content().string(containsString(name)));
        assertTokenWorks(token);

        revoke(app, session);

        assertTokenRefused(token);
        mockMvc.perform(get(PAGE).session(session)).andExpect(content().string(not(containsString(name))));
    }

    @Test
    void revokingStopsTheTokenAndMakesTheAppAskAgain() throws Exception {
        var name = uniqueName();
        var app = register(name, null, "read");
        var token = TestOAuth.userToken(mockMvc, app, session, "read");
        assertThat(TestOAuth.authorize(mockMvc, app, session, "read")).contains("code=");

        revoke(app, session);

        assertTokenRefused(token);
        assertThat(TestOAuth.authorize(mockMvc, app, session, "read")).contains("/oauth/consent");
        mockMvc.perform(get(PAGE).param("revoked", "").session(session))
                .andExpect(content().string(containsString("no longer has access")))
                .andExpect(content().string(not(containsString(name))));
    }

    @Test
    void revokingLeavesEverythingElseWorking() throws Exception {
        var app = register(uniqueName(), null, "read");
        var token = TestOAuth.userToken(mockMvc, app, session, "read");
        var otherApp = register(uniqueName(), null, "read");
        var tokenForOtherApp = TestOAuth.userToken(mockMvc, otherApp, session, "read");
        var otherSession = TestOAuth.logIn(mockMvc, OTHER_USER, PASSWORD);
        var otherUsersToken = TestOAuth.userToken(mockMvc, app, otherSession, "read");
        var appToken = appToken(app);

        revoke(app, session);

        assertTokenRefused(token);
        assertTokenWorks(tokenForOtherApp);
        assertTokenWorks(otherUsersToken);
        assertThat(TestOAuth.authorize(mockMvc, app, otherSession, "read")).contains("code=");
        mockMvc.perform(get("/api/v1/apps/verify_credentials").header("Authorization", "Bearer " + appToken))
                .andExpect(status().isOk());
    }

    @Test
    void aCodeCannotBeExchangedAfterRevoking() throws Exception {
        var app = register(uniqueName(), null, "read");
        var callback = TestOAuth.approve(mockMvc, app, session, TestOAuth.authorize(mockMvc, app, session, "read"));

        revoke(app, session);

        mockMvc.perform(TestOAuth.tokenRequest(app, callback)).andExpect(status().isBadRequest());
    }

    @Test
    void revokingAnAppYouNeverAuthorizedChangesNothing() throws Exception {
        var app = register(uniqueName(), null, "read");
        var otherSession = TestOAuth.logIn(mockMvc, OTHER_USER, PASSWORD);
        var otherUsersToken = TestOAuth.userToken(mockMvc, app, otherSession, "read");

        revoke(app, session);

        assertTokenWorks(otherUsersToken);
        assertThat(consents(app)).isOne();
    }

    @Test
    void revokingNeedsACsrfToken() throws Exception {
        var app = register(uniqueName(), null, "read");
        var token = TestOAuth.userToken(mockMvc, app, session, "read");

        mockMvc.perform(post(PAGE + "/" + app.get("id") + "/revoke").session(session)).andExpect(status().isForbidden());

        assertTokenWorks(token);
    }

    @Test
    void needsALogin() throws Exception {
        mockMvc.perform(get(PAGE)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post(PAGE + "/anything/revoke").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void settingsLinkToThePage() throws Exception {
        mockMvc.perform(get("/settings").session(session))
                .andExpect(content().string(containsString("href=\"" + PAGE + "\"")));
    }

    private static String uniqueName() {
        return "App " + UUID.randomUUID();
    }

    private Map<String, String> register(String name, String website, String scopes) throws Exception {
        var request = post("/api/v1/apps")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_name", name)
                .param("redirect_uris", "https://app.example/callback")
                .param("scopes", scopes);
        if (website != null) {
            request.param("website", website);
        }
        var body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Map.of(
                "id", JsonPath.read(body, "$.id"),
                "client_id", JsonPath.read(body, "$.client_id"),
                "client_secret", JsonPath.read(body, "$.client_secret"));
    }

    private void revoke(Map<String, String> app, MockHttpSession session) throws Exception {
        mockMvc.perform(post(PAGE + "/" + app.get("id") + "/revoke").with(csrf()).session(session))
                .andExpect(redirectedUrl(PAGE + "?revoked"));
    }

    private String appToken(Map<String, String> app) throws Exception {
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret"))
                        .param("scope", "read"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private long consents(Map<String, String> app) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization_consent WHERE registered_client_id = :id")
                .param("id", app.get("id"))
                .query(Long.class)
                .single();
    }

    private void assertTokenWorks(String token) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/verify_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void assertTokenRefused(String token) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/verify_credentials").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
