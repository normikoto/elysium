package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.content.PollService;
import dev.biribiri.elysium.content.RemotePoll;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://polls.example")
@AutoConfigureMockMvc
class PollsIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Duration WAIT = Duration.ofSeconds(30);

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

    @Autowired
    private PollService polls;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM status").update();
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void createsAPollFromJson() throws Exception {
        mockMvc.perform(json(post("/api/v1/statuses"), alice(), """
                        {"status": "Which?", "poll": {"options": ["Tea", "Coffee"], "expires_in": 3600, "multiple": true}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll.options[0].title").value("Tea"))
                .andExpect(jsonPath("$.poll.options[1].title").value("Coffee"))
                .andExpect(jsonPath("$.poll.options[0].votes_count").value(0))
                .andExpect(jsonPath("$.poll.multiple").value(true))
                .andExpect(jsonPath("$.poll.expired").value(false))
                .andExpect(jsonPath("$.poll.voters_count").value(0))
                .andExpect(jsonPath("$.poll.voted").value(false))
                .andExpect(jsonPath("$.poll.own_votes", empty()))
                .andExpect(jsonPath("$.poll.expires_at").isString());
    }

    @Test
    void createsAPollFromAForm() throws Exception {
        var id = createPoll(form(post("/api/v1/statuses"), alice())
                .param("status", "Which?")
                .param("poll[options][]", "Tea", "Coffee", "Water")
                .param("poll[expires_in]", "600"));
        mockMvc.perform(withToken(get("/api/v1/polls/" + id), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.options[2].title").value("Water"))
                .andExpect(jsonPath("$.multiple").value(false))
                .andExpect(jsonPath("$.voters_count").value(nullValue()));
    }

    @Test
    void refusesPollsThatBreakItsLimits() throws Exception {
        for (var poll : List.of(
                "{\"options\": [\"Only\"], \"expires_in\": 600}",
                "{\"options\": [\"a\", \"b\", \"c\", \"d\", \"e\"], \"expires_in\": 600}",
                "{\"options\": [\"Same\", \"Same\"], \"expires_in\": 600}",
                "{\"options\": [\"a\", \" \"], \"expires_in\": 600}",
                "{\"options\": [\"a\", \"" + "b".repeat(51) + "\"], \"expires_in\": 600}",
                "{\"options\": [\"a\", \"b\"], \"expires_in\": 60}",
                "{\"options\": [\"a\", \"b\"], \"expires_in\": 3000000}",
                "{\"options\": [\"a\", \"b\"]}",
                "{\"options\": \"a\", \"expires_in\": 600}",
                "\"a poll\"")) {
            mockMvc.perform(json(post("/api/v1/statuses"), alice(), "{\"status\": \"Which?\", \"poll\": " + poll + "}"))
                    .andExpect(status().isUnprocessableContent());
        }
        mockMvc.perform(json(post("/api/v1/statuses"), alice(), """
                        {"status": "Which?", "media_ids": ["%s"], "poll": {"options": ["a", "b"], "expires_in": 600}}
                        """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: A status can't have both media and a poll"));
        assertThat(jdbc.sql("SELECT count(*) FROM status").query(Long.class).single()).isZero();
    }

    @Test
    void ignoresAnEmptyPoll() throws Exception {
        mockMvc.perform(json(post("/api/v1/statuses"), alice(), "{\"status\": \"Hello\", \"poll\": {}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll").value(nullValue()));
        mockMvc.perform(form(post("/api/v1/statuses"), alice()).param("status", "Hello").param("poll[expires_in]", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll").value(nullValue()));
    }

    @Test
    void countsVotes() throws Exception {
        var id = createPoll(alice(), false, false);
        mockMvc.perform(form(post("/api/v1/polls/" + id + "/votes"), bob()).param("choices[]", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voted").value(true))
                .andExpect(jsonPath("$.own_votes[0]").value(1))
                .andExpect(jsonPath("$.options[1].votes_count").value(1))
                .andExpect(jsonPath("$.votes_count").value(1));
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: You have already voted on this poll"));
        mockMvc.perform(get("/api/v1/polls/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[1].votes_count").value(1))
                .andExpect(jsonPath("$.voted").doesNotExist())
                .andExpect(jsonPath("$.own_votes").doesNotExist());
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id), alice()))
                .andExpect(jsonPath("$.poll.votes_count").value(1))
                .andExpect(jsonPath("$.poll.voted").value(false));
    }

    @Test
    void countsEachChoiceOfAMultipleChoicePoll() throws Exception {
        var id = createPoll(alice(), true, false);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0, 1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.votes_count").value(2))
                .andExpect(jsonPath("$.voters_count").value(1))
                .andExpect(jsonPath("$.own_votes[1]").value(1));
    }

    @Test
    void refusesChoicesThatDoNotFitThePoll() throws Exception {
        var id = createPoll(alice(), false, false);
        for (var choices : List.of("[0, 1]", "[2]", "[-1]", "[]", "[\"x\"]")) {
            mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": " + choices + "}"))
                    .andExpect(status().isUnprocessableContent());
        }
        var multiple = createPoll(alice(), true, false);
        mockMvc.perform(json(post("/api/v1/polls/" + multiple + "/votes"), bob(), "{\"choices\": [1, 1]}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT count(*) FROM poll_vote").query(Long.class).single()).isZero();
    }

    @Test
    void hidesTotalsUntilThePollEnds() throws Exception {
        var id = createPoll(alice(), false, true);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].votes_count").value(nullValue()))
                .andExpect(jsonPath("$.own_votes[0]").value(0));
        expire(id);
        mockMvc.perform(get("/api/v1/polls/" + id))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.options[0].votes_count").value(1));
    }

    @Test
    void refusesVotesOnceThePollEndsAndTellsItsVoters() throws Exception {
        var id = createPoll(alice(), false, false);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0]}"))
                .andExpect(status().isOk());
        expire(id);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), carol(), "{\"choices\": [0]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: The poll has already ended"));

        assertThat(polls.endDue()).isEqualTo(1);
        assertThat(polls.endDue()).isZero();
        await().atMost(WAIT).untilAsserted(() -> assertThat(jdbc.sql("""
                        SELECT a.username FROM inbox_notification n JOIN actor a ON a.id = n.recipient_id
                        WHERE n.type = 'poll' AND n.status_id = :id ORDER BY a.username
                        """).param("id", UUID.fromString(id)).query(String.class).list())
                .containsExactly("alice", "bob"));
        mockMvc.perform(withToken(get("/api/v1/notifications").param("types[]", "poll"), bob()))
                .andExpect(jsonPath("$[0].type").value("poll"))
                .andExpect(jsonPath("$[0].status.poll.expired").value(true));
    }

    @Test
    void anEditThatChangesTheOptionsResetsTheVotes() throws Exception {
        var id = createPoll(alice(), false, false);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0]}"))
                .andExpect(status().isOk());

        mockMvc.perform(json(put("/api/v1/statuses/" + id), alice(), """
                        {"status": "Which?", "poll": {"options": ["Tea", "Coffee"], "expires_in": 600, "hide_totals": true}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited_at").isString());
        mockMvc.perform(get("/api/v1/polls/" + id)).andExpect(jsonPath("$.votes_count").value(1));

        mockMvc.perform(json(put("/api/v1/statuses/" + id), alice(), """
                        {"status": "Which?", "poll": {"options": ["Tea", "Juice"], "expires_in": 600}}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll.options[1].title").value("Juice"))
                .andExpect(jsonPath("$.poll.votes_count").value(0));
        mockMvc.perform(withToken(get("/api/v1/statuses/" + id + "/history"), alice()))
                .andExpect(jsonPath("$[0].poll.options[1].title").value("Coffee"))
                .andExpect(jsonPath("$[2].poll.options[1].title").value("Juice"));
    }

    @Test
    void anEditKeepsOrRemovesThePoll() throws Exception {
        var id = createPoll(alice(), false, false);
        mockMvc.perform(json(put("/api/v1/statuses/" + id), alice(), "{\"status\": \"Which one?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll.options[0].title").value("Tea"));
        mockMvc.perform(json(put("/api/v1/statuses/" + id), alice(), "{\"status\": \"Which one?\", \"poll\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poll").value(nullValue()));
        mockMvc.perform(get("/api/v1/polls/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void showsTheCountsAPollsServerGave() throws Exception {
        var id = remotePoll(new RemotePoll(List.of("Yes", "No"), List.of(3, 4), true, Instant.now().plusSeconds(600),
                null, 5)).toString();
        mockMvc.perform(withToken(get("/api/v1/polls/" + id), alice()))
                .andExpect(jsonPath("$.options[0].votes_count").value(3))
                .andExpect(jsonPath("$.voters_count").value(5));
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), alice(), "{\"choices\": [0, 1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].votes_count").value(4))
                .andExpect(jsonPath("$.options[1].votes_count").value(5))
                .andExpect(jsonPath("$.voters_count").value(6));
    }

    @Test
    void doesNotFindAPollThatCanNotBeSeen() throws Exception {
        var id = createPoll(json(post("/api/v1/statuses"), alice(), """
                {"status": "Which?", "visibility": "direct", "poll": {"options": ["a", "b"], "expires_in": 600}}"""));
        mockMvc.perform(withToken(get("/api/v1/polls/" + id), bob())).andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [0]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/polls/" + UUID.randomUUID()), bob())).andExpect(status().isNotFound());
    }

    @Test
    void showsThePollOnTheStatusPage() throws Exception {
        var id = createPoll(alice(), false, false);
        mockMvc.perform(json(post("/api/v1/polls/" + id + "/votes"), bob(), "{\"choices\": [1]}"));
        mockMvc.perform(get("/users/alice/statuses/" + id).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Coffee")))
                .andExpect(content().string(containsString("100%")))
                .andExpect(content().string(containsString("1 vote")));
    }

    @Test
    void reportsItsLimits() throws Exception {
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(jsonPath("$.configuration.polls.max_options").value(4))
                .andExpect(jsonPath("$.configuration.polls.max_characters_per_option").value(50))
                .andExpect(jsonPath("$.configuration.polls.min_expiration").value(300))
                .andExpect(jsonPath("$.configuration.polls.max_expiration").value(2629746));
    }

    // Helpers

    private String createPoll(String token, boolean multiple, boolean hideTotals) throws Exception {
        return createPoll(json(post("/api/v1/statuses"), token, """
                {"status": "Which?", "poll": {"options": ["Tea", "Coffee"], "expires_in": 600, "multiple": %s,
                 "hide_totals": %s}}""".formatted(multiple, hideTotals)));
    }

    private String createPoll(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void expire(String id) {
        jdbc.sql("UPDATE poll SET expires_at = now() - interval '1 second' WHERE status_id = :id")
                .param("id", UUID.fromString(id))
                .update();
    }

    private UUID remotePoll(RemotePoll poll) {
        var host = "h" + UUID.randomUUID() + ".test";
        var uri = URI.create("https://" + host + "/users/s");
        var author = actors.upsertRemote(
                new RemoteActorProfile(uri, "s", host, URI.create(uri + "/inbox"), null, null, null, null));
        return statuses.storeRemote(new RemoteStatus(
                URI.create("https://" + host + "/notes/" + UUID.randomUUID()), null, author.id(), "<p>Which?</p>", null,
                null, Visibility.PUBLIC, false, null, Instant.now(), null, Map.of(), List.of(), List.of(), null, null,
                poll)).orElseThrow();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token, String body) {
        return withToken(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder form(MockHttpServletRequestBuilder request, String token) {
        return withToken(request, token).contentType(MediaType.APPLICATION_FORM_URLENCODED);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String alice() throws Exception {
        return token("alice");
    }

    private String bob() throws Exception {
        return token("bob");
    }

    private String carol() throws Exception {
        return token("carol");
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
