package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin API and page for domain blocks. What a block does is tested with what it affects, such as in
 * {@code InboxIntegrationTests} and {@code ActivityDeliveryIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://blocks.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class DomainBlocksIntegrationTests {

    private static final String PATH = "/api/v1/admin/domain_blocks";
    private static final String PAGE = "/admin/domain-blocks";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "blocksadmin";
    private static final String USER = "blocksuser";

    /**
     * Logging in is slow because of Argon2, so each token is only created once.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM domain_block").update();
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        TestAccounts.makeAdmin(jdbc, actors.findLocalByUsername(ADMIN).orElseThrow().id());
    }

    // Creating

    @Test
    void createsFromAForm() throws Exception {
        var domain = newDomain();

        mockMvc.perform(withToken(post(PATH), write())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("domain", domain.toUpperCase())
                        .param("severity", "suspend")
                        .param("private_comment", "Spam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.domain").value(domain))
                .andExpect(jsonPath("$.digest").isString())
                .andExpect(jsonPath("$.created_at").isString())
                .andExpect(jsonPath("$.severity").value("suspend"))
                .andExpect(jsonPath("$.reject_media").value(true))
                .andExpect(jsonPath("$.reject_reports").value(true))
                .andExpect(jsonPath("$.private_comment").value("Spam"))
                .andExpect(jsonPath("$.public_comment").value(nullValue()))
                .andExpect(jsonPath("$.obfuscate").value(false));
    }

    @Test
    void refusesToCreateWithoutASeverity() throws Exception {
        mockMvc.perform(withToken(post(PATH), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + newDomain() + "\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("severity")));

        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"silence", "noop", "SUSPEND"})
    void refusesOtherSeverities(String severity) throws Exception {
        mockMvc.perform(withToken(post(PATH), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + newDomain() + "\",\"severity\":\"" + severity + "\"}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.spam.example", "blocks.example"})
    void refusesDomainsThatCannotBeBlocked(String domain) throws Exception {
        create(domain).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error").isString());

        assertThat(count()).isZero();
    }

    @Test
    void allowsASubdomainOfThisServer() throws Exception {
        create("sub.blocks.example").andExpect(status().isOk());
    }

    @Test
    void refusesABlockThatAlreadyExists() throws Exception {
        var domain = newDomain();
        var id = id(create(domain).andExpect(status().isOk()));

        create(domain)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.existing_domain_block.id").value(id));
    }

    @Test
    void refusesADomainThatABroaderBlockCovers() throws Exception {
        var domain = newDomain();
        var id = id(create(domain).andExpect(status().isOk()));

        create("sub." + domain)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.existing_domain_block.id").value(id));
    }

    @Test
    void allowsABroaderBlockThanAnExistingOne() throws Exception {
        var domain = newDomain();
        create("sub." + domain).andExpect(status().isOk());

        create(domain).andExpect(status().isOk());
    }

    @Test
    void refusesACommentThatIsTooLong() throws Exception {
        mockMvc.perform(withToken(post(PATH), write())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("domain", newDomain())
                        .param("severity", "suspend")
                        .param("private_comment", "a".repeat(1001)))
                .andExpect(status().isUnprocessableContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\\u0000b", "a\\ud83db"})
    void refusesACommentThatCannotBeStored(String comment) throws Exception {
        var body = "{\"domain\":\"" + newDomain() + "\",\"severity\":\"suspend\",\"private_comment\":\"" + comment + "\"}";
        mockMvc.perform(withToken(post(PATH), write()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent());

        var id = id(create(newDomain()));
        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"private_comment\":\"" + comment + "\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void refusesAValueOfTheWrongType() throws Exception {
        mockMvc.perform(withToken(post(PATH), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":[\"a.example\"],\"severity\":\"suspend\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    // Reading

    @Test
    void readsABlock() throws Exception {
        var domain = newDomain();
        var id = id(create(domain));

        mockMvc.perform(withToken(get(PATH + "/" + id), read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value(domain));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "00000000-0000-7000-8000-000000000000"})
    void answersNotFoundForAnUnknownBlock(String id) throws Exception {
        mockMvc.perform(withToken(get(PATH + "/" + id), read()))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete(PATH + "/" + id), write()))
                .andExpect(status().isNotFound());
    }

    @Test
    void pagesThroughBlocksNewestFirst() throws Exception {
        var first = id(create(newDomain()));
        Thread.sleep(2);
        var second = id(create(newDomain()));
        Thread.sleep(2);
        var third = id(create(newDomain()));

        mockMvc.perform(withToken(get(PATH).param("limit", "2"), read()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(third))
                .andExpect(jsonPath("$[1].id").value(second))
                .andExpect(header().string(HttpHeaders.LINK, containsString(
                        "<https://blocks.example" + PATH + "?limit=2&max_id=" + second + ">; rel=\"next\"")))
                .andExpect(header().string(HttpHeaders.LINK, containsString("min_id=" + third)));

        mockMvc.perform(withToken(get(PATH).param("max_id", second), read()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(first));
        mockMvc.perform(withToken(get(PATH).param("min_id", first).param("limit", "1"), read()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(second));
        mockMvc.perform(withToken(get(PATH).param("since_id", first).param("limit", "1"), read()))
                .andExpect(jsonPath("$[0].id").value(third));
    }

    @Test
    void refusesABadCursor() throws Exception {
        mockMvc.perform(withToken(get(PATH).param("max_id", "nope"), read()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(get(PATH).param("limit", "nope"), read()))
                .andExpect(status().isUnprocessableContent());
    }

    // Updating

    @Test
    void updatesTheCommentFromAForm() throws Exception {
        var id = id(create(newDomain()));

        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("private_comment=Still%20spam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.private_comment").value("Still spam"));
    }

    @Test
    void updatesTheCommentFromJsonAndClearsIt() throws Exception {
        var id = id(create(newDomain()));

        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"private_comment\":\"Spam\"}"))
                .andExpect(jsonPath("$.private_comment").value("Spam"));
        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"suspend\"}"))
                .andExpect(jsonPath("$.private_comment").value("Spam"));
        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"private_comment\":\"\"}"))
                .andExpect(jsonPath("$.private_comment").value(nullValue()));
    }

    @Test
    void refusesToChangeABlockToAnotherSeverity() throws Exception {
        var id = id(create(newDomain()));

        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"silence\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void doesNotChangeTheDomain() throws Exception {
        var domain = newDomain();
        var id = id(create(domain));

        mockMvc.perform(withToken(put(PATH + "/" + id), write())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"other.example\"}"))
                .andExpect(jsonPath("$.domain").value(domain));
    }

    // Deleting

    @Test
    void deletesABlock() throws Exception {
        var id = id(create(newDomain()));

        mockMvc.perform(withToken(delete(PATH + "/" + id), write()))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        assertThat(count()).isZero();
    }

    // Who can use this

    @Test
    void needsAToken() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void needsTheRightScope() throws Exception {
        mockMvc.perform(withToken(get(PATH), write())).andExpect(status().isForbidden());
        mockMvc.perform(withToken(post(PATH), read())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + newDomain() + "\",\"severity\":\"suspend\"}"))
                .andExpect(status().isForbidden());
        assertThat(count()).isZero();
    }

    @Test
    void theBroaderAdminScopesCountToo() throws Exception {
        mockMvc.perform(withToken(get(PATH), token(ADMIN, "admin:read"))).andExpect(status().isOk());
        var domain = newDomain();
        mockMvc.perform(withToken(post(PATH), token(ADMIN, "admin:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"" + domain + "\",\"severity\":\"suspend\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void regularUsersCannotGetAccess() throws Exception {
        var token = token(USER, "admin:read:domain_blocks read");

        mockMvc.perform(withToken(get(PATH), token)).andExpect(status().isForbidden());
    }

    @Test
    void appTokensCannotGetAccess() throws Exception {
        var app = TestOAuth.registerApp(mockMvc, "admin:read:domain_blocks");
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "admin:read:domain_blocks")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        mockMvc.perform(withToken(get(PATH), token)).andExpect(status().isForbidden());
    }

    // The page

    @Test
    void eachFormOnThePageHasOneCsrfToken() throws Exception {
        create(newDomain());

        var html = mockMvc.perform(get(PAGE).session(logIn(ADMIN)))
                .andReturn().getResponse().getContentAsString();

        assertThat(html.split("<form", -1)).hasSize(3);
        assertThat(html.split("name=\"_csrf\"", -1)).hasSize(3);
    }

    @Test
    void addsAndRemovesABlockOnThePage() throws Exception {
        var session = logIn(ADMIN);
        var domain = newDomain();

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("domain", domain)
                        .param("comment", "<b>Spam</b>"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE));
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(content().string(containsString(domain)))
                .andExpect(content().string(containsString("&lt;b&gt;Spam&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>Spam</b>"))));

        var id = jdbc.sql("SELECT id FROM domain_block WHERE domain = :domain")
                .param("domain", domain).query(UUID.class).single();
        mockMvc.perform(post(PAGE + "/" + id + "/delete").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE));
        assertThat(count()).isZero();
    }

    @Test
    void showsWhyABlockWasRefusedOnThePage() throws Exception {
        mockMvc.perform(post(PAGE).session(logIn(ADMIN)).with(csrf()).param("domain", "*.spam.example"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("*.spam.example")))
                .andExpect(content().string(containsString("Enter only a domain")));
    }

    @Test
    void thePageNeedsACsrfToken() throws Exception {
        mockMvc.perform(post(PAGE).session(logIn(ADMIN)).param("domain", newDomain()))
                .andExpect(status().isForbidden());
        assertThat(count()).isZero();
    }

    @Test
    void regularUsersCannotUseThePage() throws Exception {
        var session = logIn(USER);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("domain", newDomain()))
                .andExpect(status().isForbidden());
        assertThat(count()).isZero();
    }

    @Test
    void anonymousVisitorsAreSentToLogIn() throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
    }

    // Helpers

    private ResultActions create(String domain) throws Exception {
        return mockMvc.perform(withToken(post(PATH), write())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domain\":\"" + domain + "\",\"severity\":\"suspend\"}"));
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static String newDomain() {
        return "s" + UUID.randomUUID().toString().replace("-", "") + ".example";
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM domain_block").query(Long.class).single();
    }

    private String read() throws Exception {
        return token(ADMIN, "admin:read:domain_blocks");
    }

    private String write() throws Exception {
        return token(ADMIN, "admin:write:domain_blocks");
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private void ensureAccount(String username) throws Exception {
        if (actors.findLocalByUsername(username).isEmpty()) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
    }

    private MockHttpSession logIn(String username) throws Exception {
        return TestOAuth.logIn(mockMvc, username, PASSWORD);
    }

    private String token(String username, String scope) throws Exception {
        var cached = TOKENS.get(username + " " + scope);
        if (cached != null) {
            return cached;
        }
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(username + " " + scope, token);
        return token;
    }
}
