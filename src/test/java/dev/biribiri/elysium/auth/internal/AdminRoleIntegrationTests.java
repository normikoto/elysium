package dev.biribiri.elysium.auth.internal;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.TokenPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class AdminRoleIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String REDIRECT_URI = "https://app.example/callback";
    private static final AtomicInteger nextAccount = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private ActorService actors;

    @Autowired
    private TokenIntrospector introspector;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    // Admin pages. Nothing is mounted there yet, so getting past security gives a 404.

    @Test
    void anonymousVisitorsCannotReachAdminPages() throws Exception {
        mockMvc.perform(get("/admin/jobs")).andExpect(status().isForbidden());
    }

    @Test
    void regularUsersCannotReachAdminPages() throws Exception {
        var account = account(AccountRole.USER);

        mockMvc.perform(get("/admin/jobs").session(logIn(account))).andExpect(status().isForbidden());
    }

    @Test
    void adminsCanReachAdminPages() throws Exception {
        var account = account(AccountRole.ADMIN);

        mockMvc.perform(get("/admin/jobs").session(logIn(account))).andExpect(status().isNotFound());
    }

    @Test
    void removingTheRoleAppliesToAnExistingSession() throws Exception {
        var account = account(AccountRole.ADMIN);
        var session = logIn(account);
        mockMvc.perform(get("/admin/jobs").session(session)).andExpect(status().isNotFound());

        accounts.setRole(account.id(), AccountRole.USER);

        mockMvc.perform(get("/admin/jobs").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void givingTheRoleAppliesToAnExistingSession() throws Exception {
        var account = account(AccountRole.USER);
        var session = logIn(account);
        mockMvc.perform(get("/admin/jobs").session(session)).andExpect(status().isForbidden());

        accounts.setRole(account.id(), AccountRole.ADMIN);

        mockMvc.perform(get("/admin/jobs").session(session)).andExpect(status().isNotFound());
    }

    @Test
    void tokensCannotReachAdminPages() throws Exception {
        var account = account(AccountRole.ADMIN);
        var token = userToken(registerApp("read admin:read"), logIn(account), "read admin:read");

        mockMvc.perform(get("/admin/jobs").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // Tokens

    @Test
    void anAdminsTokenKeepsItsAdminScopes() throws Exception {
        var account = account(AccountRole.ADMIN);
        var token = userToken(registerApp("read admin:read"), logIn(account), "read admin:read");

        assertThat(introspect(token).scopes()).contains("read", "admin:read", "admin:read:accounts");
    }

    @Test
    void adminScopesStopWorkingAsSoonAsTheAccountIsNotAnAdmin() throws Exception {
        var account = account(AccountRole.ADMIN);
        var token = userToken(registerApp("read admin:read"), logIn(account), "read admin:read");

        // Written directly, because setRole would also revoke the token, and this has to hold without that.
        jdbc.sql("UPDATE account_credential SET role = 'USER' WHERE actor_id = :actorId")
                .param("actorId", account.id())
                .update();

        var scopes = introspect(token).scopes();
        assertThat(scopes).contains("read", "read:statuses");
        assertThat(scopes).noneMatch(OAuthScopes::isAdmin);
    }

    @Test
    void changingTheRoleRevokesTokensWithAdminScopes() throws Exception {
        var account = account(AccountRole.ADMIN);
        var token = userToken(registerApp("read admin:read"), logIn(account), "read admin:read");
        var plainToken = userToken(registerApp("read"), logIn(account), "read");

        accounts.setRole(account.id(), AccountRole.USER);

        assertThatThrownBy(() -> introspect(token)).isInstanceOf(BadOpaqueTokenException.class);
        assertThat(introspect(plainToken).scopes()).contains("read");
    }

    @Test
    void appTokensNeverHaveAdminScopes() throws Exception {
        var scopes = introspect(appToken(registerApp("read admin:read admin:write"), "read admin:read admin:write"))
                .scopes();

        assertThat(scopes).contains("read");
        assertThat(scopes).noneMatch(OAuthScopes::isAdmin);
    }

    @Test
    void anAppTokenWithOnlyAdminScopesHasNoScopes() throws Exception {
        var scopes = introspect(appToken(registerApp("admin:read"), "admin:read")).scopes();

        assertThat(scopes).isEqualTo(Set.of());
    }

    // Consent

    @Test
    void theConsentPageLeavesOutAdminScopesForRegularUsers() throws Exception {
        var session = logIn(account(AccountRole.USER));
        var consent = authorize(registerApp("read admin:read"), session, "read admin:read");

        mockMvc.perform(get(URI.create(consent)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<code>read</code>")))
                .andExpect(content().string(not(containsString("<code>admin:read</code>"))))
                .andExpect(content().string(not(containsString("value=\"admin:read\""))))
                .andExpect(content().string(containsString("isn't an admin")))
                .andExpect(content().string(containsString(">Authorize<")));
    }

    @Test
    void theConsentPageShowsAdminScopesToAdmins() throws Exception {
        var session = logIn(account(AccountRole.ADMIN));
        var consent = authorize(registerApp("read admin:read"), session, "read admin:read");

        mockMvc.perform(get(URI.create(consent)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<code>admin:read</code>")))
                .andExpect(content().string(containsString("value=\"admin:read\"")))
                .andExpect(content().string(not(containsString("isn't an admin"))));
    }

    @Test
    void theConsentPageOnlyOffersDenyForOnlyAdminScopes() throws Exception {
        var session = logIn(account(AccountRole.USER));
        var consent = authorize(registerApp("admin:read"), session, "admin:read");

        mockMvc.perform(get(URI.create(consent)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("only asked for admin permissions")))
                .andExpect(content().string(not(containsString(">Authorize<"))))
                .andExpect(content().string(containsString(">Deny<")));
    }

    @Test
    void aForgedApprovalDoesNotGiveARegularUserAdminScopes() throws Exception {
        var account = account(AccountRole.USER);
        var session = logIn(account);
        var app = registerApp("read admin:read");
        var consent = authorize(app, session, "read admin:read");

        var callback = submitConsent(app, session, consent, "read", "admin:read");

        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", app.clientId())
                        .param("client_secret", app.clientSecret())
                        .param("code", queryParameter(callback, "code"))
                        .param("redirect_uri", REDIRECT_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.scope")).isEqualTo("read");
        assertThat(jdbc.sql("""
                        SELECT authorities FROM oauth2_authorization_consent
                        WHERE principal_name = :principalName AND registered_client_id = :clientId
                        """)
                .param("principalName", account.id().toString())
                .param("clientId", app.id())
                .query(String.class)
                .single()).isEqualTo("SCOPE_read");
    }

    @Test
    void aForgedApprovalOfOnlyAdminScopesIsADenial() throws Exception {
        var session = logIn(account(AccountRole.USER));
        var app = registerApp("admin:read");
        var consent = authorize(app, session, "admin:read");

        var callback = submitConsent(app, session, consent, "admin:read");

        assertThat(callback).startsWith(REDIRECT_URI + "?error=access_denied");
    }

    @Test
    void anApprovalFromBeforeTheRoleExistedHasNoEffect() throws Exception {
        var account = account(AccountRole.USER);
        var session = logIn(account);
        var app = registerApp("read admin:read");
        jdbc.sql("""
                        INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                        VALUES (:clientId, :principalName, 'SCOPE_read,SCOPE_admin:read')
                        """)
                .param("clientId", app.id())
                .param("principalName", account.id().toString())
                .update();

        var callback = authorize(app, session, "read admin:read");

        // Spring skips the consent page for scopes that were already approved.
        assertThat(callback).startsWith(REDIRECT_URI + "?code=");
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", app.clientId())
                        .param("client_secret", app.clientSecret())
                        .param("code", queryParameter(callback, "code"))
                        .param("redirect_uri", REDIRECT_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(introspect(JsonPath.read(body, "$.access_token")).scopes()).noneMatch(OAuthScopes::isAdmin);
    }

    // Helpers

    private record Account(UUID id, String username) {
    }

    private record App(String id, String clientId, String clientSecret) {
    }

    private Account account(AccountRole role) {
        var username = "adminrole" + nextAccount.incrementAndGet();
        assertThat(accounts.createAccount(username, PASSWORD)).isPresent();
        var id = actors.findLocalByUsername(username).orElseThrow().id();
        accounts.setRole(id, role);
        return new Account(id, username);
    }

    private MockHttpSession logIn(Account account) throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", account.username())
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("error"));
        return session;
    }

    private App registerApp(String scopes) throws Exception {
        var body = mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_name", "Test")
                        .param("redirect_uris", REDIRECT_URI)
                        .param("scopes", scopes))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new App(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.client_id"),
                JsonPath.read(body, "$.client_secret"));
    }

    private String authorize(App app, MockHttpSession session, String scope) throws Exception {
        return mockMvc.perform(get("/oauth/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", app.clientId())
                        .queryParam("redirect_uri", REDIRECT_URI)
                        .queryParam("scope", scope))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String submitConsent(App app, MockHttpSession session, String consentUrl, String... scopes)
            throws Exception {
        var request = post("/oauth/authorize").session(session)
                .param("client_id", app.clientId())
                .param("state", queryParameter(consentUrl, "state"));
        for (var scope : scopes) {
            request.param("scope", scope);
        }
        return mockMvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String userToken(App app, MockHttpSession session, String scope) throws Exception {
        var callback = submitConsent(app, session, authorize(app, session, scope), scope.split(" "));
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "authorization_code")
                        .param("client_id", app.clientId())
                        .param("client_secret", app.clientSecret())
                        .param("code", queryParameter(callback, "code"))
                        .param("redirect_uri", REDIRECT_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private String appToken(App app, String scope) throws Exception {
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("client_id", app.clientId())
                        .param("client_secret", app.clientSecret())
                        .param("scope", scope))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private TokenPrincipal introspect(String token) {
        return (TokenPrincipal) introspector.introspect(token);
    }

    private static String queryParameter(String url, String name) {
        var value = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
