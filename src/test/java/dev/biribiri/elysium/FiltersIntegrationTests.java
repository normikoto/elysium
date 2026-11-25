package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.PostFilterCache;
import dev.biribiri.elysium.content.PostFilters;
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

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Filters through the v2 and v1 APIs, and how they change what's listed.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class FiltersIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private PostFilters filters;
    @Autowired
    private PostFilterCache cache;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM post_filter").update();
        cache.clear();
        jdbc.sql("DELETE FROM status").update();
        jdbc.sql("DELETE FROM follow").update();
        jdbc.sql("DELETE FROM notification").update();
    }

    // The v2 API

    @Test
    void createsListsAndDeletesFilters() throws Exception {
        var token = token("filteralice");
        var created = mockMvc.perform(withToken(post("/api/v2/filters"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Spoilers", "context": ["home", "public"], "filter_action": "hide",
                                 "expires_in": 3600,
                                 "keywords_attributes": [{"keyword": "finale", "whole_word": false}, {"keyword": "ending"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Spoilers"))
                .andExpect(jsonPath("$.context", contains("home", "public")))
                .andExpect(jsonPath("$.filter_action").value("hide"))
                .andExpect(jsonPath("$.expires_at").isString())
                .andExpect(jsonPath("$.keywords[*].keyword", contains("finale", "ending")))
                .andExpect(jsonPath("$.keywords[*].whole_word", contains(false, true)))
                .andExpect(jsonPath("$.statuses", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(id)));
        mockMvc.perform(withToken(get("/api/v2/filters/" + id), token))
                .andExpect(jsonPath("$.keywords", hasSize(2)));

        mockMvc.perform(withToken(delete("/api/v2/filters/" + id), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(withToken(get("/api/v2/filters/" + id), token)).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM post_filter_keyword").query(Long.class).single()).isZero();
    }

    @Test
    void readsFormBodiesAsRailsDoes() throws Exception {
        var token = token("filteralice");
        mockMvc.perform(withToken(post("/api/v2/filters"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "Form")
                        .param("context[]", "home", "notifications")
                        .param("keywords_attributes[][keyword]", "one", "two")
                        .param("keywords_attributes[][whole_word]", "false", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context", contains("home", "notifications")))
                .andExpect(jsonPath("$.filter_action").value("warn"))
                .andExpect(jsonPath("$.expires_at").isEmpty())
                .andExpect(jsonPath("$.keywords[*].keyword", contains("one", "two")))
                .andExpect(jsonPath("$.keywords[*].whole_word", contains(false, true)));

        mockMvc.perform(withToken(post("/api/v2/filters"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("title", "Numbered")
                        .param("context[]", "thread")
                        .param("keywords_attributes[0][keyword]", "first")
                        .param("keywords_attributes[1][keyword]", "second")
                        .param("keywords_attributes[1][whole_word]", "false")
                        // A blank row is ignored, as Rails ignores one.
                        .param("keywords_attributes[2][keyword]", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords[*].keyword", contains("first", "second")))
                .andExpect(jsonPath("$.keywords[*].whole_word", contains(true, false)));
    }

    @Test
    void updatesChangeOnlyWhatTheyName() throws Exception {
        var token = token("filteralice");
        var created = createFilter(token, """
                {"title": "Old", "context": ["home"], "keywords_attributes": [{"keyword": "keep"}, {"keyword": "drop"}]}
                """);
        String id = JsonPath.read(created, "$.id");
        String keep = JsonPath.read(created, "$.keywords[0].id");
        String drop = JsonPath.read(created, "$.keywords[1].id");

        mockMvc.perform(withToken(put("/api/v2/filters/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "New", "keywords_attributes": [
                                  {"id": "%s", "whole_word": false},
                                  {"id": "%s", "_destroy": true},
                                  {"keyword": "added"}]}
                                """.formatted(keep, drop)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"))
                .andExpect(jsonPath("$.context", contains("home")))
                .andExpect(jsonPath("$.keywords[*].keyword", contains("keep", "added")))
                .andExpect(jsonPath("$.keywords[0].whole_word").value(false));

        // An ID that isn't one of the filter's keywords leaves everything as it was.
        mockMvc.perform(withToken(put("/api/v2/filters/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Newer", "keywords_attributes": [{"id": "%s", "_destroy": true}]}
                                """.formatted(drop)))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v2/filters/" + id), token))
                .andExpect(jsonPath("$.title").value("New"));
    }

    @Test
    void keywordsAndStatusesHaveTheirOwnEndpoints() throws Exception {
        var token = token("filteralice");
        String id = JsonPath.read(createFilter(token, """
                {"title": "Parts", "context": ["home"]}"""), "$.id");

        var keyword = mockMvc.perform(withToken(post("/api/v2/filters/" + id + "/keywords"), token)
                        .param("keyword", "word"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("word"))
                .andExpect(jsonPath("$.whole_word").value(true))
                .andReturn().getResponse().getContentAsString();
        String keywordId = JsonPath.read(keyword, "$.id");
        mockMvc.perform(withToken(put("/api/v2/filters/keywords/" + keywordId), token)
                        .param("whole_word", "false"))
                .andExpect(jsonPath("$.keyword").value("word"))
                .andExpect(jsonPath("$.whole_word").value(false));
        mockMvc.perform(withToken(get("/api/v2/filters/" + id + "/keywords"), token))
                .andExpect(jsonPath("$[*].id", contains(keywordId)));

        var statusId = createStatus(token, "a status to filter");
        var entry = mockMvc.perform(withToken(post("/api/v2/filters/" + id + "/statuses"), token)
                        .param("status_id", statusId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status_id").value(statusId))
                .andReturn().getResponse().getContentAsString();
        String entryId = JsonPath.read(entry, "$.id");
        mockMvc.perform(withToken(post("/api/v2/filters/" + id + "/statuses"), token).param("status_id", statusId))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(get("/api/v2/filters/statuses/" + entryId), token))
                .andExpect(jsonPath("$.status_id").value(statusId));

        mockMvc.perform(withToken(delete("/api/v2/filters/statuses/" + entryId), token)).andExpect(status().isOk());
        mockMvc.perform(withToken(delete("/api/v2/filters/keywords/" + keywordId), token)).andExpect(status().isOk());
        // The filter stays, as on Mastodon.
        mockMvc.perform(withToken(get("/api/v2/filters/" + id), token))
                .andExpect(jsonPath("$.keywords", hasSize(0)))
                .andExpect(jsonPath("$.statuses", hasSize(0)));
    }

    @Test
    void anotherUsersFiltersCantBeFound() throws Exception {
        var created = createFilter(token("filteralice"), """
                {"title": "Mine", "context": ["home"], "keywords_attributes": [{"keyword": "secret"}]}""");
        String id = JsonPath.read(created, "$.id");
        String keywordId = JsonPath.read(created, "$.keywords[0].id");
        var bob = token("filterbob");

        mockMvc.perform(withToken(get("/api/v2/filters"), bob)).andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get("/api/v2/filters/" + id), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete("/api/v2/filters/" + id), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v2/filters/keywords/" + keywordId), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/filters/" + keywordId), bob)).andExpect(status().isNotFound());
        mockMvc.perform(withToken(post("/api/v2/filters/" + id + "/keywords"), bob).param("keyword", "x"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesWhatMastodonRefuses() throws Exception {
        var token = token("filteralice");
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("context[]", "home"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Title can't be blank"));
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("title", "No context"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Context can't be blank"));
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("title", "x").param("context[]", "lists"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("title", "x").param("context[]", "home")
                        .param("filter_action", "explode"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("title", "x").param("context[]", "home")
                        .param("expires_in", "soon"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v2/filters"), token).param("title", "x".repeat(257))
                        .param("context[]", "home"))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT count(*) FROM post_filter").query(Long.class).single()).isZero();
    }

    // The v1 API

    @Test
    void v1FiltersAreKeywords() throws Exception {
        var token = token("filteralice");
        var created = mockMvc.perform(withToken(post("/api/v1/filters"), token)
                        .param("phrase", "Spoiler")
                        .param("context[]", "home", "notifications")
                        .param("irreversible", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value("Spoiler"))
                .andExpect(jsonPath("$.irreversible").value(true))
                .andExpect(jsonPath("$.whole_word").value(false))
                .andExpect(jsonPath("$.expires_at").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(jsonPath("$[0].title").value("Spoiler"))
                .andExpect(jsonPath("$[0].filter_action").value("hide"))
                .andExpect(jsonPath("$[0].keywords[0].id").value(id));

        mockMvc.perform(withToken(put("/api/v1/filters/" + id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phrase": "Spoilers", "context": ["home"], "irreversible": false, "whole_word": true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value("Spoilers"))
                .andExpect(jsonPath("$.context", contains("home")))
                .andExpect(jsonPath("$.irreversible").value(false))
                .andExpect(jsonPath("$.whole_word").value(true));
        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(jsonPath("$[0].title").value("Spoilers"));

        mockMvc.perform(withToken(delete("/api/v1/filters/" + id), token)).andExpect(status().isOk());
        // Its filter has nothing left, so it goes too.
        mockMvc.perform(withToken(get("/api/v2/filters"), token)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void v1CantChangeWhatOtherKeywordsShare() throws Exception {
        var token = token("filteralice");
        var created = createFilter(token, """
                {"title": "Shared", "context": ["home"],
                 "keywords_attributes": [{"keyword": "one"}, {"keyword": "two"}]}""");
        String one = JsonPath.read(created, "$.keywords[0].id");
        String two = JsonPath.read(created, "$.keywords[1].id");

        mockMvc.perform(withToken(get("/api/v1/filters"), token))
                .andExpect(jsonPath("$[*].id", contains(one, two)))
                .andExpect(jsonPath("$[*].phrase", contains("one", "two")));
        mockMvc.perform(withToken(put("/api/v1/filters/" + one), token).param("context[]", "public"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(put("/api/v1/filters/" + one), token)
                        .param("phrase", "uno").param("context[]", "home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value("uno"));

        mockMvc.perform(withToken(delete("/api/v1/filters/" + one), token)).andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(jsonPath("$[0].title").value("Shared"))
                .andExpect(jsonPath("$[0].keywords[*].id", contains(two)));
    }

    // What's listed

    @Test
    void statusesSayWhichFiltersTheyMatch() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        follow(alice, account("filterbob"));
        var statusId = createStatus(bob, "I love Cats and concatenation");
        String filterId = JsonPath.read(createFilter(alice, """
                {"title": "Pets", "context": ["thread"],
                 "keywords_attributes": [{"keyword": "cats"}, {"keyword": "cat"}]}"""),
                "$.id");

        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[0].id").value(statusId))
                .andExpect(jsonPath("$[0].filtered[0].filter.id").value(filterId))
                .andExpect(jsonPath("$[0].filtered[0].filter.keywords").doesNotExist())
                .andExpect(jsonPath("$[0].filtered[0].keyword_matches", contains("Cats")))
                .andExpect(jsonPath("$[0].filtered[0].status_matches").isEmpty());
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), bob))
                .andExpect(jsonPath("$[0].filtered", hasSize(0)));
        mockMvc.perform(get("/api/v1/statuses/" + statusId))
                .andExpect(jsonPath("$.filtered").doesNotExist());
    }

    @Test
    void hidingLeavesStatusesOutOfListsInItsContexts() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        var bobAccount = account("filterbob");
        follow(alice, bobAccount);
        var hidden = createStatus(bob, "the finale was great");
        var shown = createStatus(bob, "nothing to see");
        createFilter(alice, """
                {"title": "Finale", "context": ["home", "account"], "filter_action": "hide",
                 "keywords_attributes": [{"keyword": "finale"}]}""");

        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[*].id", contains(shown)));
        mockMvc.perform(withToken(get("/api/v1/accounts/" + bobAccount.id() + "/statuses"), alice))
                .andExpect(jsonPath("$[*].id", contains(shown)));
        mockMvc.perform(withToken(get("/api/v1/timelines/public"), alice))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(hidden, shown)));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + hidden), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filtered[0].filter.filter_action").value("hide"));
    }

    @Test
    void theUsersOwnStatusesArentFiltered() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        createFilter(alice, """
                {"title": "Finale", "context": ["home", "account", "notifications"], "filter_action": "hide",
                 "keywords_attributes": [{"keyword": "finale"}]}""");
        var own = createStatus(alice, "my finale review");
        mockMvc.perform(withToken(post("/api/v1/statuses/" + own + "/favourite"), bob)).andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[*].id", contains(own)))
                .andExpect(jsonPath("$[0].filtered", hasSize(0)));
        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM notification").query(Long.class)
                .single() == 1);
        mockMvc.perform(withToken(get("/api/v1/notifications"), alice))
                .andExpect(jsonPath("$[*].status.id", contains(own)));
    }

    @Test
    void longV1PhrasesNameTheirFiltersShortened() throws Exception {
        var token = token("filteralice");
        var phrase = "p".repeat(PostFilters.MAX_KEYWORD_LENGTH);
        mockMvc.perform(withToken(post("/api/v1/filters"), token).param("phrase", phrase).param("context[]", "home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phrase").value(phrase));
        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(jsonPath("$[0].title").value("p".repeat(PostFilters.MAX_TITLE_LENGTH)));
    }

    @Test
    void emptyFlagsAreLeftOut() throws Exception {
        var token = token("filteralice");
        mockMvc.perform(withToken(post("/api/v2/filters"), token)
                        .param("title", "Empty")
                        .param("context[]", "home")
                        .param("keywords_attributes[0][keyword]", "word")
                        .param("keywords_attributes[0][whole_word]", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords[0].whole_word").value(true));
    }

    @Test
    void v1DoesntTurnBlurIntoAWarning() throws Exception {
        var token = token("filteralice");
        String keywordId = JsonPath.read(createFilter(token, """
                {"title": "Blur", "context": ["home"], "filter_action": "blur",
                 "keywords_attributes": [{"keyword": "one"}, {"keyword": "two"}]}"""), "$.keywords[0].id");
        mockMvc.perform(withToken(put("/api/v1/filters/" + keywordId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phrase": "uno", "irreversible": false, "whole_word": 0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.irreversible").value(false))
                .andExpect(jsonPath("$.whole_word").value(false));
        mockMvc.perform(withToken(get("/api/v2/filters"), token))
                .andExpect(jsonPath("$[0].filter_action").value("blur"));
    }

    @Test
    void statusFiltersCoverBoosts() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        var carol = token("filtercarol");
        follow(alice, account("filtercarol"));
        var statusId = createStatus(bob, "boost me");
        mockMvc.perform(withToken(post("/api/v1/statuses/" + statusId + "/reblog"), carol)).andExpect(status().isOk());
        String filterId = JsonPath.read(createFilter(alice, """
                {"title": "That one", "context": ["home"]}"""), "$.id");
        mockMvc.perform(withToken(post("/api/v2/filters/" + filterId + "/statuses"), alice).param("status_id", statusId))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$[0].reblog.id").value(statusId))
                .andExpect(jsonPath("$[0].filtered[0].status_matches", contains(statusId)))
                .andExpect(jsonPath("$[0].reblog.filtered[0].status_matches", contains(statusId)));

        mockMvc.perform(withToken(put("/api/v2/filters/" + filterId), alice).param("filter_action", "hide"))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void hiddenStatusesTakeTheirNotificationsWithThem() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        createFilter(alice, """
                {"title": "Quiet", "context": ["notifications"], "filter_action": "hide",
                 "keywords_attributes": [{"keyword": "spam"}]}""");
        createStatus(bob, "@filteralice buy spam");
        var kept = createStatus(bob, "@filteralice hello");

        await().atMost(WAIT).until(() -> jdbc.sql("SELECT count(*) FROM notification").query(Long.class)
                .single() == 2);
        mockMvc.perform(withToken(get("/api/v1/notifications"), alice))
                .andExpect(jsonPath("$[*].status.id", contains(kept)));
    }

    @Test
    void expiredFiltersStopApplying() throws Exception {
        var alice = token("filteralice");
        var bob = token("filterbob");
        follow(alice, account("filterbob"));
        createStatus(bob, "the finale");
        createFilter(alice, """
                {"title": "Finale", "context": ["home"], "filter_action": "hide",
                 "keywords_attributes": [{"keyword": "finale"}]}""");
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice)).andExpect(jsonPath("$", hasSize(0)));

        jdbc.sql("UPDATE post_filter SET expires_at = now() - interval '1 minute'").update();
        cache.clear();
        mockMvc.perform(withToken(get("/api/v1/timelines/home"), alice))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].filtered", hasSize(0)));
        mockMvc.perform(withToken(get("/api/v2/filters"), alice)).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void removingAnAccountsFiltersLeavesOthers() throws Exception {
        createFilter(token("filteralice"), """
                {"title": "A", "context": ["home"], "keywords_attributes": [{"keyword": "a"}]}""");
        createFilter(token("filterbob"), """
                {"title": "B", "context": ["home"]}""");
        filters.removeForActor(account("filteralice").id());
        assertThat(jdbc.sql("SELECT title FROM post_filter").query(String.class).list()).isEqualTo(List.of("B"));
    }

    // Helpers

    private String createFilter(String token, String json) throws Exception {
        return mockMvc.perform(withToken(post("/api/v2/filters"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String createStatus(String token, String text) throws Exception {
        var body = mockMvc.perform(withToken(post("/api/v1/statuses"), token)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", text)
                        .param("visibility", "public"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void follow(String token, Actor followed) throws Exception {
        mockMvc.perform(withToken(post("/api/v1/accounts/" + followed.id() + "/follow"), token))
                .andExpect(status().isOk());
    }

    private Actor account(String username) throws Exception {
        token(username);
        return actors.findLocalByUsername(username).orElseThrow();
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
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
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, "read write");
        TOKENS.put(username, token);
        return token;
    }
}
