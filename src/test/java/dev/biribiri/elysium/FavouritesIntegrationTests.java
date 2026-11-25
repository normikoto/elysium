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

/**
 * {@code GET /api/v1/favourites}, and the lists of who favourited and boosted a status.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class FavouritesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String FAVOURITES = "/api/v1/favourites";
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
        jdbc.sql("DELETE FROM account_mute").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("UPDATE actor SET suspended_at = NULL").update();
    }

    @Test
    void listsOnlyTheUsersOwnFavourites() throws Exception {
        var id = create(bob(), "public");

        favourite(alice(), id);
        list(alice(), FAVOURITES).andExpect(jsonPath("$[*].id", contains(id)))
                .andExpect(jsonPath("$[0].favourited").value(true));
        list(bob(), FAVOURITES).andExpect(jsonPath("$", empty()));

        mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/unfavourite"), alice()))
                .andExpect(status().isOk());
        list(alice(), FAVOURITES).andExpect(jsonPath("$", empty()));
    }

    @Test
    void listsTheNewestFavouriteFirstAndPagesByFavourite() throws Exception {
        var first = create(bob(), "public");
        var second = create(bob(), "public");
        var third = create(bob(), "public");
        favourite(alice(), second);
        favourite(alice(), third);
        favourite(alice(), first);

        var page = list(alice(), FAVOURITES + "?limit=2").andExpect(jsonPath("$[*].id", contains(first, third)));
        var cursor = next(page);
        assertThat(cursor).isNotIn(first, second, third);
        assertThat(cursor).isEqualTo(likeId(alice(), third));

        list(alice(), FAVOURITES + "?limit=2&max_id=" + cursor).andExpect(jsonPath("$[*].id", contains(second)));
        list(alice(), FAVOURITES + "?limit=1&min_id=" + likeId(alice(), second))
                .andExpect(jsonPath("$[*].id", contains(third)));
        list(alice(), FAVOURITES + "?since_id=" + likeId(alice(), second))
                .andExpect(jsonPath("$[*].id", contains(first, third)));
    }

    @Test
    void leavesOutStatusesTheUserCanNoLongerSeeWithoutEndingThePaging() throws Exception {
        var kept = create(carol(), "public");
        var hidden = create(bob(), "public");
        favourite(alice(), kept);
        favourite(alice(), hidden);
        mockMvc.perform(withToken(post("/api/v1/accounts/" + accountId(alice()) + "/block"), bob()))
                .andExpect(status().isOk());

        var page = list(alice(), FAVOURITES + "?limit=1").andExpect(jsonPath("$", empty()));
        list(alice(), FAVOURITES + "?limit=1&max_id=" + next(page)).andExpect(jsonPath("$[*].id", contains(kept)));
    }

    @Test
    void favouritesNeedTheScopeAndAUser() throws Exception {
        mockMvc.perform(withToken(get(FAVOURITES), token("fav_dave", "write"))).andExpect(status().isForbidden());
        mockMvc.perform(withToken(get(FAVOURITES), token("fav_dave", "read:favourites"))).andExpect(status().isOk());
        mockMvc.perform(withToken(get(FAVOURITES), appToken())).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get(FAVOURITES)).andExpect(status().isUnauthorized());
    }

    @Test
    void listsWhoFavouritedNewestFirstWithoutAToken() throws Exception {
        var id = create(bob(), "public");
        favourite(alice(), id);
        favourite(carol(), id);

        mockMvc.perform(get("/api/v1/statuses/" + id + "/favourited_by")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(accountId(carol()), accountId(alice()))));
        list(bob(), "/api/v1/statuses/" + id + "/favourited_by")
                .andExpect(jsonPath("$[*].username", contains("fav_carol", "fav_alice")));

        var page = list(bob(), "/api/v1/statuses/" + id + "/favourited_by?limit=1")
                .andExpect(jsonPath("$[*].username", contains("fav_carol")));
        assertThat(next(page)).isEqualTo(likeId(carol(), id));
        list(bob(), "/api/v1/statuses/" + id + "/favourited_by?max_id=" + next(page))
                .andExpect(jsonPath("$[*].username", contains("fav_alice")));
        list(bob(), "/api/v1/statuses/" + id + "/favourited_by?min_id=" + likeId(alice(), id))
                .andExpect(jsonPath("$[*].username", contains("fav_carol")));
    }

    @Test
    void aBoostsIdListsWhoFavouritedAndBoostedWhatItBoosts() throws Exception {
        var id = create(bob(), "public");
        favourite(alice(), id);
        var boost = reblog(carol(), id, "public");

        list(bob(), "/api/v1/statuses/" + boost + "/favourited_by")
                .andExpect(jsonPath("$[*].username", contains("fav_alice")));
        list(bob(), "/api/v1/statuses/" + boost + "/reblogged_by")
                .andExpect(jsonPath("$[*].username", contains("fav_carol")));
    }

    @Test
    void leavesOutSuspendedAccounts() throws Exception {
        var id = create(bob(), "public");
        favourite(alice(), id);
        favourite(erin(), id);
        reblog(erin(), id, "public");
        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE username = 'fav_erin'").update();

        list(bob(), "/api/v1/statuses/" + id + "/favourited_by")
                .andExpect(jsonPath("$[*].username", contains("fav_alice")));
        list(bob(), "/api/v1/statuses/" + id + "/reblogged_by").andExpect(jsonPath("$", empty()));
    }

    @Test
    void listsWhoBoostedNewestFirstLeavingOutFollowersOnlyBoosts() throws Exception {
        var id = create(bob(), "public");
        reblog(alice(), id, "public");
        reblog(carol(), id, "unlisted");

        mockMvc.perform(get("/api/v1/statuses/" + id + "/reblogged_by")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains("fav_carol", "fav_alice")));
        var page = list(bob(), "/api/v1/statuses/" + id + "/reblogged_by?limit=1")
                .andExpect(jsonPath("$[*].username", contains("fav_carol")));
        list(bob(), "/api/v1/statuses/" + id + "/reblogged_by?max_id=" + next(page))
                .andExpect(jsonPath("$[*].username", contains("fav_alice")));

        var followersOnly = create(bob(), "private");
        reblog(bob(), followersOnly, "public");
        list(bob(), "/api/v1/statuses/" + followersOnly + "/reblogged_by").andExpect(jsonPath("$", empty()));
    }

    @Test
    void leavesOutAccountsHiddenFromTheViewerOrBlockingThem() throws Exception {
        var id = create(bob(), "public");
        for (var token : new String[]{alice(), carol(), dave()}) {
            favourite(token, id);
            reblog(token, id, "public");
        }
        mockMvc.perform(withToken(post("/api/v1/accounts/" + accountId(alice()) + "/block"), bob()))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + accountId(carol()) + "/mute"), bob()))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + accountId(bob()) + "/block"), dave()))
                .andExpect(status().isOk());

        for (var list : new String[]{"/favourited_by", "/reblogged_by"}) {
            list(bob(), "/api/v1/statuses/" + id + list).andExpect(jsonPath("$", empty()));
            mockMvc.perform(get("/api/v1/statuses/" + id + list)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].username", contains("fav_dave", "fav_carol", "fav_alice")));
            list(carol(), "/api/v1/statuses/" + id + list)
                    .andExpect(jsonPath("$[*].username", contains("fav_dave", "fav_carol", "fav_alice")));
        }
    }

    @Test
    void refusesStatusesTheViewerCantSee() throws Exception {
        var followersOnly = create(bob(), "private");
        favourite(bob(), followersOnly);

        for (var list : new String[]{"/favourited_by", "/reblogged_by"}) {
            mockMvc.perform(get("/api/v1/statuses/" + followersOnly + list)).andExpect(status().isNotFound());
            mockMvc.perform(withToken(get("/api/v1/statuses/" + followersOnly + list), carol()))
                    .andExpect(status().isNotFound());
            list(bob(), "/api/v1/statuses/" + followersOnly + list);
            mockMvc.perform(get("/api/v1/statuses/" + UUID.randomUUID() + list)).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/statuses/nope" + list)).andExpect(status().isNotFound());
        }
    }

    @Test
    void interactionListsNeedReadAccountsWithAToken() throws Exception {
        var id = create(bob(), "public");

        mockMvc.perform(withToken(get("/api/v1/statuses/" + id + "/favourited_by"), token("fav_dave", "write")))
                .andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id + "/reblogged_by"), token("fav_dave", "read:accounts")))
                .andExpect(status().isOk());
    }

    private void favourite(String token, String id) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/favourite"), token)).andExpect(status().isOk());
    }

    private String reblog(String token, String id, String visibility) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses/" + id + "/reblog"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("visibility", visibility))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
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

    private String likeId(String token, String statusId) throws Exception {
        return jdbc.sql("SELECT id FROM status_like WHERE status_id = :status AND actor_id = :actor")
                .param("status", UUID.fromString(statusId)).param("actor", UUID.fromString(accountId(token)))
                .query(UUID.class).single().toString();
    }

    private String create(String token, String visibility) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Worth a star")
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
        return token("fav_alice", "read write follow");
    }

    private String bob() throws Exception {
        return token("fav_bob", "read write follow");
    }

    private String carol() throws Exception {
        return token("fav_carol", "read write follow");
    }

    private String dave() throws Exception {
        return token("fav_dave", "read write follow");
    }

    /**
     * Only for the test that suspends the account, since its token stops working then.
     */
    private String erin() throws Exception {
        return token("fav_erin", "read write follow");
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
