package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class OAuthIntegrationTests {

    static final String PASSWORD = "correct horse battery staple";

    private static final String REDIRECT_URI = "https://app.example/callback";
    private static final String OOB = "urn:ietf:wg:oauth:2.0:oob";
    private static final Pattern OOB_CODE = Pattern.compile("<code>([^<]+)</code>");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // Sign-ups are off in this class, and the code that creates accounts is internal to the auth module.
    @BeforeEach
    void createAccount() {
        if (actors.findLocalByUsername("oauthuser").isPresent()) {
            return;
        }
        var hash = passwordEncoder.encode(PASSWORD);
        actors.createLocalIfAbsent("oauthuser", actor -> jdbc.sql("""
                        INSERT INTO account_credential (actor_id, password_hash) VALUES (:actorId, :hash)
                        """)
                .param("actorId", actor.id())
                .param("hash", hash)
                .update());
    }

    // App registration

    @Test
    void registersAppsFromForms() throws Exception {
        mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_name", "Test Application")
                        .param("redirect_uris", OOB)
                        .param("scopes", "read write push")
                        .param("website", "https://app.example"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.name").value("Test Application"))
                .andExpect(jsonPath("$.website").value("https://app.example"))
                .andExpect(jsonPath("$.scopes[0]").value("read"))
                .andExpect(jsonPath("$.scopes[2]").value("push"))
                .andExpect(jsonPath("$.redirect_uri").value(OOB))
                .andExpect(jsonPath("$.redirect_uris[0]").value(OOB))
                .andExpect(jsonPath("$.client_id").isString())
                .andExpect(jsonPath("$.client_secret").isString())
                .andExpect(jsonPath("$.client_secret_expires_at").value(0));
    }

    @Test
    void registersAppsFromJsonWithSeveralRedirectUris() throws Exception {
        mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"client_name": "Test", "redirect_uris": ["https://a.example/1", "https://a.example/2"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirect_uri").value("https://a.example/1\nhttps://a.example/2"))
                .andExpect(jsonPath("$.redirect_uris[1]").value("https://a.example/2"))
                .andExpect(jsonPath("$.scopes[0]").value("read"))
                .andExpect(jsonPath("$.website").isEmpty());
    }

    @Test
    void rejectsInvalidRegistrations() throws Exception {
        var valid = Map.of("client_name", "Test", "redirect_uris", REDIRECT_URI);
        var invalid = List.of(
                Map.of("redirect_uris", REDIRECT_URI),
                Map.of("client_name", "Test"),
                Map.of("client_name", "x".repeat(256), "redirect_uris", REDIRECT_URI),
                Map.of("client_name", "Test", "redirect_uris", "not/absolute"),
                Map.of("client_name", "Test", "redirect_uris", "javascript:alert(1)"),
                Map.of("client_name", "Test", "redirect_uris", "DATA:text/html,hi"),
                Map.of("client_name", "Test", "redirect_uris", "https://a.example/cb#fragment"),
                Map.of("client_name", "Test", "redirect_uris", "https://a.example/a,https://a.example/b"),
                Map.of("client_name", "Test", "redirect_uris", REDIRECT_URI, "scopes", "crypto"),
                Map.of("client_name", "Test", "redirect_uris", REDIRECT_URI, "scopes", "read,write"),
                Map.of("client_name", "Test", "redirect_uris", REDIRECT_URI, "website", "javascript:alert(1)"));

        assertThat(registration(valid).getStatus()).isEqualTo(200);
        for (var request : invalid) {
            var response = registration(request);
            assertThat(response.getStatus()).as(request.toString()).isEqualTo(422);
            assertThat(JsonPath.<String>read(response.getContentAsString(), "$.error")).startsWith("Validation failed: ");
        }
    }

    @Test
    void hashesClientSecrets() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        assertThat(jdbc.sql("SELECT client_secret FROM oauth2_registered_client WHERE client_id = :id")
                .param("id", app.clientId()).query(String.class).single())
                .startsWith("{sha256}")
                .doesNotContain(app.clientSecret());
    }

    // Client credentials

    @Test
    void issuesAppTokensWithTheDefaultScope() throws Exception {
        var app = registerApp("read write", REDIRECT_URI);

        mockMvc.perform(appTokenRequest(app))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("read"))
                .andExpect(jsonPath("$.created_at").isNumber())
                .andExpect(jsonPath("$.expires_in").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist());
    }

    @Test
    void acceptsBasicClientAuthentication() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(post("/oauth/token")
                        .with(httpBasic(app.clientId(), app.clientSecret()))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsScopesTheAppDidNotRegisterFor() throws Exception {
        var app = registerApp("write", REDIRECT_URI);
        // Omitting the scope asks for read, which this app doesn't have.
        mockMvc.perform(appTokenRequest(app))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mockMvc.perform(appTokenRequest(app).param("scope", "push"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    void rejectsBadClientCredentials() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("client_id", app.clientId())
                        .param("client_secret", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.error_description").isString());
    }

    @Test
    void rejectsTokenRequestsWithNoClientCredentials() throws Exception {
        mockMvc.perform(post("/oauth/token").param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void appVerifyCredentialsNeverReturnsTheSecret() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = appToken(app);

        mockMvc.perform(withToken(get("/api/v1/apps/verify_credentials"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(app.id()))
                .andExpect(jsonPath("$.name").value("Test"))
                .andExpect(jsonPath("$.redirect_uris[0]").value(REDIRECT_URI))
                .andExpect(jsonPath("$.client_id").doesNotExist())
                .andExpect(jsonPath("$.client_secret").doesNotExist());
    }

    @Test
    void appVerifyCredentialsAcceptsUserTokens() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(withToken(get("/api/v1/apps/verify_credentials"), userToken(app, "read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(app.id()));
    }

    // Authorization code

    @Test
    void completesTheCodeFlow() throws Exception {
        var app = registerApp("read write", REDIRECT_URI);
        var session = loggedInSession();

        var consent = authorize(app, session, "read write", "client-state");
        assertThat(consent).startsWith("http://localhost/oauth/consent");
        var callback = approve(app, session, consent);

        var parameters = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
        assertThat(callback).startsWith(REDIRECT_URI + "?code=");
        assertThat(parameters.getFirst("state")).isEqualTo("client-state");

        mockMvc.perform(tokenRequest(app, parameters.getFirst("code"), REDIRECT_URI))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("read write"))
                .andExpect(jsonPath("$.created_at").isNumber())
                .andExpect(jsonPath("$.expires_in").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist());
    }

    @Test
    void omittedScopeMeansRead() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var consent = authorize(app, loggedInSession(), null, null);
        assertThat(queryParameter(consent, "scope")).isEqualTo("read");
    }

    @Test
    void anAppCannotRequestAGranularScopeItOnlyHasTheParentOf() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var redirect = authorize(app, loggedInSession(), "read:statuses", null);
        assertThat(redirect).startsWith(REDIRECT_URI + "?error=invalid_scope");
    }

    @Test
    void supportsPkce() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var session = loggedInSession();
        var verifier = "a".repeat(64);
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var request = authorizeRequest(app, session, "read", null)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
        var code = queryParameter(
                approve(app, session, mockMvc.perform(request).andReturn().getResponse().getRedirectedUrl()), "code");

        mockMvc.perform(tokenRequest(app, code, REDIRECT_URI).param("code_verifier", "b".repeat(64)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        // A wrong verifier is rejected before the code is looked at, so the code still works.
        mockMvc.perform(tokenRequest(app, code, REDIRECT_URI).param("code_verifier", verifier))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsPkceMethodsOtherThanS256() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var request = authorizeRequest(app, loggedInSession(), "read", null)
                .queryParam("code_challenge", "a".repeat(64))
                .queryParam("code_challenge_method", "plain");
        var redirect = mockMvc.perform(request).andReturn().getResponse().getRedirectedUrl();
        assertThat(redirect).contains("error=invalid_request");
    }

    @Test
    void aCodeCanOnlyBeUsedOnce() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var code = authorizationCode(app, "read");

        mockMvc.perform(tokenRequest(app, code, REDIRECT_URI)).andExpect(status().isOk());
        mockMvc.perform(tokenRequest(app, code, REDIRECT_URI))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void anotherClientCannotUseACode() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var other = registerApp("read", REDIRECT_URI);
        var code = authorizationCode(app, "read");

        mockMvc.perform(tokenRequest(other, code, REDIRECT_URI))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void showsTheCodeForTheOutOfBandRedirect() throws Exception {
        var app = registerApp("read", OOB);
        var session = loggedInSession();
        var consent = mockMvc.perform(authorizeRequest(app, session, "read", null, OOB))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        var page = mockMvc.perform(approveRequest(app, session, consent))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("<title>Authorization code - Elysium</title>")))
                .andReturn().getResponse().getContentAsString();
        var matcher = OOB_CODE.matcher(page);
        assertThat(matcher.find()).isTrue();

        mockMvc.perform(tokenRequest(app, matcher.group(1), OOB)).andExpect(status().isOk());
    }

    @Test
    void keepsQueryParametersOnCustomSchemeRedirects() throws Exception {
        var app = registerApp("read", "myapp:callback");
        var session = loggedInSession();
        var consent = mockMvc.perform(authorizeRequest(app, session, "read", "s", "myapp:callback"))
                .andReturn().getResponse().getRedirectedUrl();

        var callback = mockMvc.perform(approveRequest(app, session, consent))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(callback).startsWith("myapp:callback?code=").endsWith("&state=s");
    }

    @Test
    void neverRedirectsToAnUnregisteredUri() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(authorizeRequest(app, loggedInSession(), "read", null, "https://evil.example/callback"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Location"));
    }

    // Consent

    @Test
    void consentPageDescribesTheRequest() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var session = loggedInSession();
        var consent = authorize(app, session, "read", null);

        mockMvc.perform(get(URI.create(consent)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Test")))
                .andExpect(content().string(containsString("oauthuser")))
                .andExpect(content().string(containsString("value=\"" + queryParameter(consent, "state") + "\"")))
                .andExpect(content().string(containsString("<title>Authorize application - Elysium</title>")))
                .andExpect(content().string(containsString("action=\"/oauth/authorize\"")))
                .andExpect(content().string(not(containsString("_csrf"))));
    }

    @Test
    void pagesAreCompleteHtmlDocuments() throws Exception {
        var page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).startsWith("<!doctype html>");
        assertThat(page).contains("<title>Log in - Elysium</title>").contains("<h1>Log in</h1>");
        assertThat(page).doesNotContain(" th:");
    }

    @Test
    void showsTheErrorForADeniedOutOfBandRedirect() throws Exception {
        var app = registerApp("read", OOB);
        var session = loggedInSession();
        var consent = mockMvc.perform(authorizeRequest(app, session, "read", null, OOB))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        mockMvc.perform(post("/oauth/authorize").session(session)
                        .param("client_id", app.clientId())
                        .param("state", queryParameter(consent, "state")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<title>Authorization failed - Elysium</title>")))
                .andExpect(content().string(containsString("<code>access_denied</code>")));
    }

    @Test
    void consentPageEscapesClientNames() throws Exception {
        var response = registration(Map.of("client_name", "<script>alert(1)</script>", "redirect_uris", REDIRECT_URI));
        var body = response.getContentAsString();
        var app = new App(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.client_id"),
                JsonPath.read(body, "$.client_secret"));
        var session = loggedInSession();

        mockMvc.perform(get(URI.create(authorize(app, session, "read", null))).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    void consentPageNeedsALogin() throws Exception {
        mockMvc.perform(get("/oauth/consent")
                        .param("client_id", "x").param("scope", "read").param("state", "y"))
                .andExpect(status().isForbidden());
    }

    @Test
    void skipsConsentForScopesAlreadyApproved() throws Exception {
        var app = registerApp("read write", REDIRECT_URI);
        var session = loggedInSession();
        approve(app, session, authorize(app, session, "read", null));

        assertThat(authorize(app, session, "read", null)).startsWith(REDIRECT_URI + "?code=");
    }

    @Test
    void asksAgainForNewScopes() throws Exception {
        var app = registerApp("read write", REDIRECT_URI);
        var session = loggedInSession();
        approve(app, session, authorize(app, session, "read", null));

        assertThat(authorize(app, session, "read write", null)).startsWith("http://localhost/oauth/consent");
    }

    @Test
    void denyingConsentIssuesNoCode() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var session = loggedInSession();

        var callback = deny(app, session, authorize(app, session, "read", "s"));

        assertThat(callback).startsWith(REDIRECT_URI + "?error=access_denied").endsWith("state=s");
        assertThat(callback).doesNotContain("code=");
    }

    /**
     * Without a fix, Spring merges the earlier approval into the denied request and issues a code for it.
     */
    @Test
    void denyingConsentIssuesNoCodeEvenWithAnEarlierApproval() throws Exception {
        var app = registerApp("read write", REDIRECT_URI);
        var session = loggedInSession();
        approve(app, session, authorize(app, session, "read", null));

        var callback = deny(app, session, authorize(app, session, "read write", null));

        assertThat(callback).startsWith(REDIRECT_URI + "?error=access_denied").doesNotContain("code=");
    }

    @Test
    void consentCannotBeSubmittedForAnotherUsersRequest() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var consent = authorize(app, loggedInSession(), "read", null);

        mockMvc.perform(approveRequest(app, new MockHttpSession(), consent))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("code="));
    }

    // Login

    @Test
    void anonymousAuthorizationGoesToLoginAndResumesAfterwards() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var session = new MockHttpSession();

        var login = mockMvc.perform(authorizeRequest(app, session, "read", "s"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(login).isEqualTo("/login");
        mockMvc.perform(get("/login").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));

        var resumed = logIn(session, "oauthuser", PASSWORD);
        assertThat(resumed).contains("/oauth/authorize").contains("client_id=" + app.clientId());
        assertThat(mockMvc.perform(get(URI.create(resumed)).session(session)).andReturn().getResponse().getRedirectedUrl())
                .startsWith("http://localhost/oauth/consent");
    }

    @Test
    void aDeniedRequestDuringLoginDoesNotLoseThePendingAuthorization() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var session = new MockHttpSession();
        mockMvc.perform(authorizeRequest(app, session, "read", null)).andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/actuator").session(session)).andExpect(status().isForbidden());

        assertThat(logIn(session, "oauthuser", PASSWORD)).contains("/oauth/authorize");
    }

    @Test
    void loginIgnoresUsernameCase() throws Exception {
        assertThat(logIn(new MockHttpSession(), "OAUTHUSER", PASSWORD)).isNotEqualTo("/login?error");
    }

    @Test
    void rejectsWrongPasswordsAndUnknownUsers() throws Exception {
        assertThat(logIn(new MockHttpSession(), "oauthuser", "wrong password")).isEqualTo("/login?error");
        assertThat(logIn(new MockHttpSession(), "nobody", PASSWORD)).isEqualTo("/login?error");
    }

    @Test
    void loginNeedsACsrfToken() throws Exception {
        mockMvc.perform(post("/login").param("username", "oauthuser").param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void logsOut() throws Exception {
        var session = loggedInSession();
        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?logout"));
    }

    @Test
    void storesPasswordsWithArgon2idAndKeepsThemOutOfAuthorizations() throws Exception {
        assertThat(jdbc.sql("""
                        SELECT c.password_hash FROM account_credential c
                        JOIN actor a ON a.id = c.actor_id WHERE a.username = 'oauthuser'
                        """).query(String.class).single())
                .startsWith("{argon2id}$argon2id$");

        var app = registerApp("read", REDIRECT_URI);
        authorizationCode(app, "read");

        assertThat(jdbc.sql("""
                        SELECT attributes FROM oauth2_authorization
                        JOIN oauth2_registered_client c ON c.id = registered_client_id
                        WHERE c.client_id = :id
                        """).param("id", app.clientId()).query(String.class).single())
                .doesNotContain("argon2");
    }

    // Tokens and scopes

    @Test
    void verifyCredentialsReturnsTheUser() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), userToken(app, "read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.username").value("oauthuser"))
                .andExpect(jsonPath("$.acct").value("oauthuser"))
                .andExpect(jsonPath("$.display_name").value(""))
                .andExpect(jsonPath("$.url").value("https://elysium.example/users/oauthuser"))
                .andExpect(jsonPath("$.avatar").value("https://elysium.example/images/missing.png"))
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.followers_count").value(0))
                .andExpect(jsonPath("$.source.privacy").value("public"))
                .andExpect(jsonPath("$.emojis").isArray());
    }

    @Test
    void placeholderImageIsPublic() throws Exception {
        mockMvc.perform(get("/images/missing.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void scopesGrantAccessThroughTheirParents() throws Exception {
        for (var scope : List.of("profile", "read:accounts", "read")) {
            var app = registerApp(scope, REDIRECT_URI);
            mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), userToken(app, scope)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rejectsTokensWithoutARequiredScope() throws Exception {
        for (var scope : List.of("write", "read:statuses", "follow", "push")) {
            var app = registerApp(scope, REDIRECT_URI);
            mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), userToken(app, scope)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("This action is outside the authorized scopes"));
        }
    }

    @Test
    void userEndpointsRejectAppTokens() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), appToken(app)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("This method requires an authenticated user"));
    }

    @Test
    void rejectsMissingAndUnknownTokens() throws Exception {
        for (var request : List.of(
                get("/api/v1/accounts/verify_credentials"),
                withToken(get("/api/v1/accounts/verify_credentials"), "unknown"),
                get("/api/v1/apps/verify_credentials"))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("The access token is invalid"));
        }
    }

    @Test
    void rejectsTokensWhoseAccountNoLongerExists() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = userToken(app, "read");
        jdbc.sql("UPDATE oauth2_authorization SET principal_name = :actorId WHERE access_token_value = :token")
                .param("actorId", UUID.randomUUID().toString())
                .param("token", token)
                .update();

        for (var path : List.of("/api/v1/accounts/verify_credentials", "/api/v1/apps/verify_credentials")) {
            mockMvc.perform(withToken(get(path), token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.error").value("The access token is invalid"));
        }
    }

    @Test
    void keepsClientSecretsCheapToCheckAfterUse() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        appToken(app);
        // Spring replaces a secret's hash after a login if the encoder says it needs upgrading.
        assertThat(jdbc.sql("SELECT client_secret FROM oauth2_registered_client WHERE client_id = :id")
                .param("id", app.clientId()).query(String.class).single())
                .startsWith("{sha256}");
    }

    /**
     * More requests at once than the connection pool has connections. Checking a token looks up its client while reading
     * the token, so if those used separate connections, these requests would all wait on each other until they timed out.
     */
    @Test
    void checksMoreTokensAtOnceThanThereAreConnections() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = userToken(app, "read");
        try (var executor = Executors.newFixedThreadPool(32)) {
            List<Callable<Integer>> requests = IntStream.range(0, 64)
                    .<Callable<Integer>>mapToObj(i -> () -> mockMvc
                            .perform(withToken(get("/api/v1/accounts/verify_credentials"), token))
                            .andReturn().getResponse().getStatus())
                    .toList();
            for (var request : executor.invokeAll(requests)) {
                assertThat(request.get()).isEqualTo(200);
            }
        }
    }

    // Revocation

    @Test
    void revokingATokenStopsItWorking() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = userToken(app, "read");
        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token)).andExpect(status().isOk());

        for (var attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(revokeRequest(app, token))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{}"));
        }

        mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotRevokeAnotherClientsToken() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var other = registerApp("read", REDIRECT_URI);
        var token = appToken(app);

        mockMvc.perform(revokeRequest(other, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("unauthorized_client"))
                .andExpect(jsonPath("$.error_description").isString());

        mockMvc.perform(withToken(get("/api/v1/apps/verify_credentials"), token)).andExpect(status().isOk());
    }

    @Test
    void revokingAnUnknownTokenSucceeds() throws Exception {
        mockMvc.perform(revokeRequest(registerApp("read", REDIRECT_URI), "unknown"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    void revocationNeedsClientCredentials() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = appToken(app);
        mockMvc.perform(post("/oauth/revoke").param("token", token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth/revoke")
                        .param("client_id", app.clientId())
                        .param("client_secret", "wrong")
                        .param("token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    // Metadata and exposure

    @Test
    void publishesOnlySupportedMetadata() throws Exception {
        var issuer = "https://elysium.example";
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value(issuer))
                .andExpect(jsonPath("$.authorization_endpoint").value(issuer + "/oauth/authorize"))
                .andExpect(jsonPath("$.token_endpoint").value(issuer + "/oauth/token"))
                .andExpect(jsonPath("$.revocation_endpoint").value(issuer + "/oauth/revoke"))
                .andExpect(jsonPath("$.app_registration_endpoint").value(issuer + "/api/v1/apps"))
                .andExpect(jsonPath("$.scopes_supported", hasItem("read:collections")))
                .andExpect(jsonPath("$.scopes_supported", hasItem("admin:write:reports")))
                .andExpect(jsonPath("$.response_types_supported[0]").value("code"))
                .andExpect(jsonPath("$.response_modes_supported[0]").value("query"))
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.grant_types_supported.length()").value(2))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported.length()").value(2))
                .andExpect(jsonPath("$.jwks_uri").doesNotExist())
                .andExpect(jsonPath("$.introspection_endpoint").doesNotExist())
                .andExpect(jsonPath("$.userinfo_endpoint").doesNotExist());
    }

    @Test
    void keepsUnusedOAuthEndpointsClosed() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        var token = appToken(app);

        mockMvc.perform(get("/oauth2/jwks")).andExpect(status().isForbidden());
        mockMvc.perform(post("/oauth2/introspect")
                        .with(httpBasic(app.clientId(), app.clientSecret()))
                        .param("token", token))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "client_credentials")
                        .param("client_id", app.clientId())
                        .param("client_secret", app.clientSecret()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLoggedInUserCannotReachInternalEndpoints() throws Exception {
        var session = loggedInSession();
        for (var path : List.of("/v3/api-docs", "/actuator", "/actuator/env",
                "/actuator/prometheus", "/not-a-real-path")) {
            mockMvc.perform(get(path).session(session)).andExpect(status().isForbidden());
        }
    }

    @Test
    void aBearerTokenCannotReachInternalEndpoints() throws Exception {
        var token = appToken(registerApp("read", REDIRECT_URI));
        mockMvc.perform(withToken(get("/actuator"), token)).andExpect(status().isForbidden());
    }

    // CORS

    @Test
    void allowsBrowserClientsOnClientEndpoints() throws Exception {
        for (var path : List.of("/oauth/token", "/oauth/revoke", "/api/v1/apps")) {
            mockMvc.perform(options(path)
                            .header("Origin", "https://client.example")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        }
        mockMvc.perform(options("/.well-known/oauth-authorization-server")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void allowsBearerTokensCrossOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/accounts/verify_credentials")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")));
    }

    @Test
    void doesNotAllowCrossOriginAuthorization() throws Exception {
        var app = registerApp("read", REDIRECT_URI);
        mockMvc.perform(options("/oauth/authorize")
                        .header("Origin", "https://client.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mockMvc.perform(authorizeRequest(app, new MockHttpSession(), "read", null)
                        .header("Origin", "https://client.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void signUpIsOffByDefault() throws Exception {
        mockMvc.perform(get("/signup")).andExpect(status().isNotFound());
        mockMvc.perform(post("/signup").with(csrf())
                        .param("username", "someone")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM actor WHERE username = 'someone'").query(Long.class).single()).isZero();
        mockMvc.perform(get("/login")).andExpect(content().string(not(containsString("/signup"))));
    }

    // Helpers

    private record App(String id, String clientId, String clientSecret) {
    }

    private MockHttpServletResponse registration(Map<String, String> fields) throws Exception {
        var request = post("/api/v1/apps").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        fields.forEach(request::param);
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private App registerApp(String scopes, String redirectUri) throws Exception {
        var response = registration(Map.of("client_name", "Test", "redirect_uris", redirectUri, "scopes", scopes));
        assertThat(response.getStatus()).isEqualTo(200);
        var body = response.getContentAsString();
        return new App(JsonPath.read(body, "$.id"), JsonPath.read(body, "$.client_id"),
                JsonPath.read(body, "$.client_secret"));
    }

    private MockHttpSession loggedInSession() throws Exception {
        var session = new MockHttpSession();
        assertThat(logIn(session, "oauthuser", PASSWORD)).isNotEqualTo("/login?error");
        return session;
    }

    private String logIn(MockHttpSession session, String username, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private MockHttpServletRequestBuilder authorizeRequest(App app, MockHttpSession session, String scope, String state) {
        return authorizeRequest(app, session, scope, state, REDIRECT_URI);
    }

    private MockHttpServletRequestBuilder authorizeRequest(
            App app, MockHttpSession session, String scope, String state, String redirectUri) {
        var request = get("/oauth/authorize").session(session)
                .queryParam("response_type", "code")
                .queryParam("client_id", app.clientId())
                .queryParam("redirect_uri", redirectUri);
        if (scope != null) {
            request.queryParam("scope", scope);
        }
        if (state != null) {
            request.queryParam("state", state);
        }
        return request;
    }

    private String authorize(App app, MockHttpSession session, String scope, String state) throws Exception {
        return mockMvc.perform(authorizeRequest(app, session, scope, state))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    /**
     * Builds what the consent page's Authorize button submits, given the URL of that page.
     */
    private MockHttpServletRequestBuilder approveRequest(App app, MockHttpSession session, String consentUrl) {
        var request = post("/oauth/authorize").session(session)
                .param("client_id", app.clientId())
                .param("state", queryParameter(consentUrl, "state"));
        for (var scope : queryParameter(consentUrl, "scope").split(" ")) {
            request.param("scope", scope);
        }
        return request;
    }

    private String approve(App app, MockHttpSession session, String consentUrl) throws Exception {
        return mockMvc.perform(approveRequest(app, session, consentUrl))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String deny(App app, MockHttpSession session, String consentUrl) throws Exception {
        return mockMvc.perform(post("/oauth/authorize").session(session)
                        .param("client_id", app.clientId())
                        .param("state", queryParameter(consentUrl, "state")))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private String authorizationCode(App app, String scope) throws Exception {
        var session = loggedInSession();
        return queryParameter(approve(app, session, authorize(app, session, scope, null)), "code");
    }

    private MockHttpServletRequestBuilder tokenRequest(App app, String code, String redirectUri) {
        return post("/oauth/token")
                .param("grant_type", "authorization_code")
                .param("client_id", app.clientId())
                .param("client_secret", app.clientSecret())
                .param("code", code)
                .param("redirect_uri", redirectUri);
    }

    private MockHttpServletRequestBuilder appTokenRequest(App app) {
        return post("/oauth/token")
                .param("grant_type", "client_credentials")
                .param("client_id", app.clientId())
                .param("client_secret", app.clientSecret());
    }

    private MockHttpServletRequestBuilder revokeRequest(App app, String token) {
        return post("/oauth/revoke")
                .param("client_id", app.clientId())
                .param("client_secret", app.clientSecret())
                .param("token", token);
    }

    private String userToken(App app, String scope) throws Exception {
        var body = mockMvc.perform(tokenRequest(app, authorizationCode(app, scope), REDIRECT_URI))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private String appToken(App app) throws Exception {
        var body = mockMvc.perform(appTokenRequest(app))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static String queryParameter(String url, String name) {
        var value = UriComponentsBuilder.fromUri(URI.create(url)).build().getQueryParams().getFirst(name);
        return value == null ? null : java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
