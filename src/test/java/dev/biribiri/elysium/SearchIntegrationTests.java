package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteProfile;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://search.example")
@AutoConfigureMockMvc
class SearchIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String SEARCH = "/api/v2/search";

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
    @Autowired
    private StatusService statuses;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM status").update();
        TestAccounts.makeSureAnAccountExists(actors);
        setIndexable(open(), true);
    }

    @Test
    void findsWhatTheViewerWroteFavouritedOrIsMentionedInAndIndexablePublicStatuses() throws Exception {
        var mine = create(searcher(), "zebra mine", "public");
        var liked = create(closed(), "zebra liked", "public");
        favourite(searcher(), liked);
        var mention = create(closed(), "zebra hello @searcher", "unlisted");
        create(closed(), "zebra unseen", "public");
        var indexable = create(open(), "zebra open", "public");
        create(open(), "zebra quiet", "unlisted");

        expectStatuses(search(searcher(), "zebra"), indexable, mention, liked, mine);
    }

    @Test
    void matchesWholeWordsPhrasesAlternativesAndExclusions() throws Exception {
        var fox = create(open(), "The quick brown fox", "public");
        var dog = create(open(), "A brown dog", "public");
        var warned = expectCreated(createRequest(open(), "hidden").param("spoiler_text", "Foxes ahead"));

        expectStatuses(search(searcher(), "FOX"), fox);
        expectStatuses(search(searcher(), "foxes"), warned);
        expectStatuses(search(searcher(), "\"brown fox\""), fox);
        expectStatuses(search(searcher(), "\"fox brown\""));
        expectStatuses(search(searcher(), "fox or dog"), dog, fox);
        expectStatuses(search(searcher(), "brown -dog"), fox);
        expectStatuses(search(searcher(), "!!! &"));
    }

    @Test
    void leavesOutFollowersOnlyStatusesTheViewerCanNoLongerSee() throws Exception {
        var openId = accountId(open());
        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/follow"), searcher())).andExpect(status().isOk());
        var secret = create(open(), "walrus secret", "private");
        favourite(searcher(), secret);
        expectStatuses(search(searcher(), "walrus"), secret);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/unfollow"), searcher())).andExpect(status().isOk());
        expectStatuses(search(searcher(), "walrus"));
    }

    @Test
    void leavesOutMutedAndBlockedAccounts() throws Exception {
        var openId = accountId(open());
        var found = create(open(), "narwhal", "public");
        expectStatuses(search(blocker(), "narwhal"), found);

        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/mute"), blocker())).andExpect(status().isOk());
        expectStatuses(search(blocker(), "narwhal"));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/unmute"), blocker())).andExpect(status().isOk());

        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/block"), blocker())).andExpect(status().isOk());
        expectStatuses(search(blocker(), "narwhal"));
        mockMvc.perform(withToken(post("/api/v1/accounts/" + openId + "/unblock"), blocker())).andExpect(status().isOk());
        expectStatuses(search(blocker(), "narwhal"), found);
    }

    @Test
    void onlyFindsStatusesForASignedInUser() throws Exception {
        create(open(), "searcher", "public");

        mockMvc.perform(get(SEARCH).param("q", "searcher"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statuses", empty()));
        mockMvc.perform(withToken(get(SEARCH).param("q", "searcher").param("type", "accounts"), searcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", not(empty())))
                .andExpect(jsonPath("$.statuses", empty()));
        mockMvc.perform(withToken(get(SEARCH).param("q", "searcher").param("type", "statuses"), searcher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", empty()))
                .andExpect(jsonPath("$.hashtags", empty()))
                .andExpect(jsonPath("$.statuses[0].content").value("<p>searcher</p>"));
        // An address names an account, even when statuses have the same words.
        expectStatuses(search(searcher(), "searcher@search.example"));
    }

    @Test
    void pagesAndFiltersByAccount() throws Exception {
        var first = create(open(), "okapi one", "public");
        var second = create(open(), "okapi two", "public");
        var third = create(open(), "okapi three", "public");
        var mine = create(searcher(), "okapi mine", "public");

        expectStatuses(search(searcher(), "okapi").param("limit", "2"), mine, third);
        expectStatuses(search(searcher(), "okapi").param("limit", "2").param("offset", "2"), second, first);
        expectStatuses(search(searcher(), "okapi").param("max_id", third), second, first);
        expectStatuses(search(searcher(), "okapi").param("min_id", second).param("limit", "1"), third);
        expectStatuses(search(searcher(), "okapi").param("account_id", accountId(open())), third, second, first);
        expectStatuses(search(searcher(), "okapi").param("account_id", "nonsense"));
        expectStatuses(search(searcher(), "okapi").param("offset", "200"));
        mockMvc.perform(search(searcher(), "okapi").param("max_id", "nonsense")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void findsAnEditedStatusByItsNewText() throws Exception {
        var id = create(open(), "before", "public");

        mockMvc.perform(withToken(put("/api/v1/statuses/" + id), open())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "after"))
                .andExpect(status().isOk());

        expectStatuses(search(searcher(), "before"));
        expectStatuses(search(searcher(), "after"), id);
    }

    @Test
    void findsPublicStatusesFromOtherServersOnlyByIndexableAccounts() throws Exception {
        var indexable = remote(true, "<p>Remote <b>quokka</b></p>");
        remote(false, "<p>Hidden quokka</p>");

        expectStatuses(search(searcher(), "quokka"), indexable);
    }

    @Test
    void stopsFindingAnAccountsStatusesWhenItStopsBeingIndexable() throws Exception {
        var found = create(open(), "pangolin", "public");
        expectStatuses(search(searcher(), "pangolin"), found);

        setIndexable(open(), false);
        expectStatuses(search(searcher(), "pangolin"));
    }

    private String remote(boolean indexable, String html) {
        var host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        var profile = new RemoteProfile("", "", "", null, null, List.of(), false, indexable, false, false, null, null);
        var author = actors.upsertRemote(new RemoteActorProfile(
                uri, "s", host, URI.create(uri + "/inbox"), null, null, null, null, null, profile));
        return statuses.storeRemote(new RemoteStatus(
                URI.create(author.uri() + "/notes/" + UUID.randomUUID()), null, author.id(), html, null,
                null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), Set.of()))
                .orElseThrow().toString();
    }

    private void setIndexable(String token, boolean indexable) throws Exception {
        mockMvc.perform(withToken(patch("/api/v1/accounts/update_credentials"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("indexable", String.valueOf(indexable)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexable").value(indexable));
    }

    private void favourite(String token, String statusId) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses/" + statusId + "/favourite"), token)).andExpect(status().isOk());
    }

    private String accountId(String token) throws Exception {
        var body = mockMvc.perform(withToken(get("/api/v1/accounts/verify_credentials"), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder search(String token, String query) {
        return withToken(get(SEARCH).param("q", query).param("type", "statuses"), token);
    }

    private void expectStatuses(MockHttpServletRequestBuilder request, String... ids) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$.statuses[*].id");
        assertThat(found).containsExactly(ids);
    }

    private String create(String token, String text, String visibility) throws Exception {
        return expectCreated(createRequest(token, text).param("visibility", visibility));
    }

    private String expectCreated(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder createRequest(String token, String text) {
        return withToken(post("/api/v1/statuses"), token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", text);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String searcher() throws Exception {
        return token("searcher");
    }

    /**
     * An account that lets anyone's search find its public statuses.
     */
    private String open() throws Exception {
        return token("openbook");
    }

    /**
     * An account that doesn't.
     */
    private String closed() throws Exception {
        return token("closedbook");
    }

    /**
     * Blocks and mutes, which would hide statuses from {@link #searcher} in other tests.
     */
    private String blocker() throws Exception {
        return token("blocker");
    }

    private String token(String username) throws Exception {
        var cached = TOKENS.get(username);
        if (cached != null) {
            return cached;
        }
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write follow");
        TOKENS.put(username, token);
        return token;
    }
}
