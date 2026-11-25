package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class BookmarksIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String BOOKMARKS = "/api/v1/bookmarks";
    private static final Pattern NEXT = Pattern.compile("<[^>]*[?&]max_id=([^&>]+)[^>]*>; rel=\"next\"");

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
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM account_block").update();
    }

    @Test
    void bookmarksAndUnbookmarksPrivately() throws Exception {
        var id = create(bob(), "public");

        bookmark(alice(), id).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.bookmarked").value(true));
        bookmark(alice(), id).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(true));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id), alice()))
                .andExpect(jsonPath("$.bookmarked").value(true));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id), bob()))
                .andExpect(jsonPath("$.bookmarked").value(false));
        list(alice(), BOOKMARKS).andExpect(jsonPath("$[*].id", contains(id)));
        list(bob(), BOOKMARKS).andExpect(jsonPath("$", empty()));

        unbookmark(alice(), id).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(false));
        unbookmark(alice(), id).andExpect(status().isOk()).andExpect(jsonPath("$.bookmarked").value(false));
        list(alice(), BOOKMARKS).andExpect(jsonPath("$", empty()));
    }

    @Test
    void aBoostsIdBookmarksWhatItBoosts() throws Exception {
        var id = create(bob(), "public");
        var body = mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/reblog"), carol()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String boost = JsonPath.read(body, "$.id");

        bookmark(alice(), boost).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        list(alice(), BOOKMARKS).andExpect(jsonPath("$[*].id", contains(id)));
        unbookmark(alice(), boost).andExpect(status().isOk());
        list(alice(), BOOKMARKS).andExpect(jsonPath("$", empty()));
    }

    @Test
    void refusesWhatTheUserCantSee() throws Exception {
        var direct = create(bob(), "direct");

        bookmark(alice(), direct).andExpect(status().isNotFound());
        bookmark(alice(), UUID.randomUUID().toString()).andExpect(status().isNotFound());
        bookmark(alice(), "nope").andExpect(status().isNotFound());
        assertThat(bookmarkCount()).isZero();
    }

    @Test
    void needsTheScopesAndAUser() throws Exception {
        var id = create(bob(), "public");

        bookmark(readOnly(), id).andExpect(status().isForbidden());
        mockMvc.perform(withToken(get(BOOKMARKS), token("dave", "write"))).andExpect(status().isForbidden());
        bookmark(appToken(), id).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(withToken(get(BOOKMARKS), appToken())).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void listsTheNewestBookmarkFirstAndPagesByBookmark() throws Exception {
        var first = create(bob(), "public");
        var second = create(bob(), "public");
        var third = create(bob(), "public");
        bookmark(alice(), second).andExpect(status().isOk());
        bookmark(alice(), third).andExpect(status().isOk());
        bookmark(alice(), first).andExpect(status().isOk());

        var page = list(alice(), BOOKMARKS + "?limit=2").andExpect(jsonPath("$[*].id", contains(first, third)));
        var cursor = next(page);
        assertThat(cursor).isNotIn(first, second, third);
        assertThat(cursor).isEqualTo(bookmarkId(third));

        list(alice(), BOOKMARKS + "?limit=2&max_id=" + cursor).andExpect(jsonPath("$[*].id", contains(second)));
        list(alice(), BOOKMARKS + "?limit=1&min_id=" + bookmarkId(second))
                .andExpect(jsonPath("$[*].id", contains(third)));
        list(alice(), BOOKMARKS + "?since_id=" + bookmarkId(second))
                .andExpect(jsonPath("$[*].id", contains(first, third)));
    }

    @Test
    void leavesOutStatusesTheUserCanNoLongerSeeWithoutEndingThePaging() throws Exception {
        var kept = create(carol(), "public");
        var hidden = create(bob(), "public");
        bookmark(alice(), kept).andExpect(status().isOk());
        bookmark(alice(), hidden).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + accountId(alice()) + "/block"), bob()))
                .andExpect(status().isOk());

        var page = list(alice(), BOOKMARKS + "?limit=1").andExpect(jsonPath("$", empty()));
        list(alice(), BOOKMARKS + "?limit=1&max_id=" + next(page)).andExpect(jsonPath("$[*].id", contains(kept)));

        // Still removed, so that it doesn't come back if the block ends.
        unbookmark(alice(), hidden).andExpect(status().isNotFound());
        assertThat(bookmarkCount()).isOne();
    }

    private ResultActions bookmark(String token, String id) throws Exception {
        return mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/bookmark"), token));
    }

    private ResultActions unbookmark(String token, String id) throws Exception {
        return mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/unbookmark"), token));
    }

    private ResultActions list(String token, String path) throws Exception {
        return mockMvc.perform(withToken(get(path), token)).andExpect(status().isOk());
    }

    private static String next(ResultActions page) {
        var link = page.andReturn().getResponse().getHeader(HttpHeaders.LINK);
        assertThat(link).isNotNull();
        var matcher = NEXT.matcher(link);
        assertThat(matcher.find()).as(link).isTrue();
        return matcher.group(1);
    }

    private String bookmarkId(String statusId) {
        return jdbc.sql("SELECT id FROM status_bookmark WHERE status_id = :id")
                .param("id", UUID.fromString(statusId)).query(UUID.class).single().toString();
    }

    private long bookmarkCount() {
        return jdbc.sql("SELECT count(*) FROM status_bookmark").query(Long.class).single();
    }

    private String create(String token, String visibility) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Worth keeping")
                        .param("visibility", visibility))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String accountId(String token) throws Exception {
        var body = mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String alice() throws Exception {
        return token("alice", "read write follow");
    }

    private String bob() throws Exception {
        return token("bob", "read write follow");
    }

    private String carol() throws Exception {
        return token("carol", "read write follow");
    }

    private String readOnly() throws Exception {
        return token("dave", "read");
    }

    private String token(String username, String scope) throws Exception {
        var key = username + " " + scope;
        var cached = TOKENS.get(key);
        if (cached != null) {
            return cached;
        }
        if (TOKENS.keySet().stream().noneMatch(existing -> existing.startsWith(username + " "))) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(key, token);
        return token;
    }

    private String appToken() throws Exception {
        var cached = TOKENS.get("app");
        if (cached != null) {
            return cached;
        }
        var app = TestOAuth.registerApp(mockMvc, "read write");
        var body = mockMvc.perform(post("/oauth/token")
                        .param("grant_type", "client_credentials")
                        .param("scope", "read write")
                        .param("client_id", app.get("client_id"))
                        .param("client_secret", app.get("client_secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        TOKENS.put("app", token);
        return token;
    }
}
