package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matcher;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.StatusDeleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.registration.enabled=true"
})
@AutoConfigureMockMvc
@RecordApplicationEvents
class StatusesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String TIMELINE = "/api/v1/timelines/public";
    private static final String VERIFY_CREDENTIALS = "/api/v1/accounts/verify_credentials";

    /**
     * Logging in is slow because of Argon2, so each token is only created once. Spring reuses the context, and with it
     * the database, for every test in this class.
     */
    private static final Map<String, String> TOKENS = new HashMap<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private ActorService actors;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM status").update();
        TestAccounts.makeSureAnAccountExists(actors);
    }

    // Creating

    @Test
    void createsFromAForm() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses"), alice())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Hello <b>world</b>")
                        .param("spoiler_text", "Greeting")
                        .param("language", "en")
                        .param("visibility", "unlisted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.created_at").value(containsString(".")))
                .andExpect(jsonPath("$.content").value("<p>Hello &lt;b&gt;world&lt;/b&gt;</p>"))
                .andExpect(jsonPath("$.spoiler_text").value("Greeting"))
                .andExpect(jsonPath("$.sensitive").value(true))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.visibility").value("unlisted"))
                .andExpect(jsonPath("$.uri").value(containsString("https://elysium.example/users/alice/statuses/")))
                .andExpect(jsonPath("$.url").value(containsString("https://elysium.example/users/alice/statuses/")))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.account.username").value("alice"))
                .andExpect(jsonPath("$.account.statuses_count").value(1))
                .andExpect(jsonPath("$.media_attachments", empty()))
                .andExpect(jsonPath("$.favourited").value(false));
    }

    @Test
    void createsFromJson() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses"), alice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "Hello", "sensitive": true, "visibility": "private", "poll": null,
                                 "media_ids": [], "scheduled_at": null, "in_reply_to_id": null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<p>Hello</p>"))
                .andExpect(jsonPath("$.sensitive").value(true))
                .andExpect(jsonPath("$.visibility").value("private"));
    }

    @Test
    void createsFromMultipart() throws Exception {
        mockMvc.perform(multipart("/api/v1/statuses")
                        .part(new MockPart("status", "Hello".getBytes(StandardCharsets.UTF_8)))
                        .part(new MockPart("sensitive", "false".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<p>Hello</p>"))
                .andExpect(jsonPath("$.sensitive").value(false))
                .andExpect(jsonPath("$.visibility").value("public"));
    }

    @Test
    void treatsEmptyFormFieldsAsMissing() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses"), alice())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Hello")
                        .param("in_reply_to_id", "")
                        .param("language", "")
                        .param("scheduled_at", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("public"))
                .andExpect(jsonPath("$.language").value(nullValue()));
    }

    @Test
    void rejectsAVisibilityThatIsEmptyOrAmbiguous() throws Exception {
        mockMvc.perform(jsonCreateRequest(alice(), "{\"status\": \"Secret\", \"visibility\": \"\"}"))
                .andExpect(status().isUnprocessableContent());
        for (var request : List.of(
                createRequest(alice(), "Secret").param("visibility", ""),
                createRequest(alice(), "Secret").param("visibility", "", "direct"),
                createRequest(alice(), "Secret").param("visibility", "public", "direct"),
                createRequest(alice(), "Secret").param("visibility[]", "private"),
                createRequest(alice(), "Secret").param("visibility[]", ""))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(containsString("Validation failed")));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM status").query(Long.class).single()).isZero();
    }

    @Test
    void idsFollowTheOrderStatusesAreCommittedIn() throws Exception {
        var token = alice();
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<String>> creates = IntStream.range(0, 200)
                    .<Callable<String>>mapToObj(i -> () -> create(token, "Status " + i))
                    .toList();
            for (var created : executor.invokeAll(creates)) {
                created.get();
            }
        }
        // Inside the lock, a transaction gets its ID at its first write, so these numbers follow the commit order.
        var byCommit = jdbc.sql("SELECT id FROM status ORDER BY xmin::text::bigint").query(String.class).list();
        var byId = jdbc.sql("SELECT id FROM status ORDER BY id").query(String.class).list();
        assertThat(byCommit).hasSize(200).isEqualTo(byId);
    }

    @Test
    void enforcesTheDefaultLengthLimit() throws Exception {
        expectCreated(createRequest(alice(), "a".repeat(5000)));
        mockMvc.perform(createRequest(alice(), "a".repeat(5001)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Text can't be longer than 5000 characters"));
    }

    @Test
    void countsEachUrlAs23CharactersTowardTheLimit() throws Exception {
        var url = "https://a.example/" + "x".repeat(100);
        expectCreated(createRequest(alice(), url + " " + "a".repeat(5000 - 23 - 1)));
        mockMvc.perform(createRequest(alice(), url + " " + "a".repeat(5000 - 23)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void rejectsInvalidStatuses() throws Exception {
        for (var body : List.of(
                "{\"status\": \"\"}",
                "{\"status\": \"a\\u0000b\"}",
                "{\"status\": \"a\\ud800b\"}",
                "{\"status\": \"Hello\", \"visibility\": \"everyone\"}",
                "{\"status\": \"Hello\", \"language\": \"not a language\"}")) {
            mockMvc.perform(jsonCreateRequest(alice(), body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(containsString("Validation failed")));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM status").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsValuesOfTheWrongTypeRatherThanIgnoringThem() throws Exception {
        for (var field : List.of(
                "\"visibility\": 1",
                "\"visibility\": {\"value\": \"direct\"}",
                "\"visibility\": [\"direct\"]",
                "\"in_reply_to_id\": 123",
                "\"sensitive\": 1",
                "\"spoiler_text\": []",
                "\"language\": true")) {
            mockMvc.perform(jsonCreateRequest(alice(), "{\"status\": \"Hello\", " + field + "}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(containsString("Validation failed")));
        }
        mockMvc.perform(jsonCreateRequest(alice(), "{\"status\": 5}")).andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT count(*) FROM status").query(Long.class).single()).isZero();
    }

    @Test
    void rejectsJsonThatIsNotAnObject() throws Exception {
        for (var body : List.of("", "null", "[]", "\"Hello\"", "{\"status\": ")) {
            mockMvc.perform(jsonCreateRequest(alice(), body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(containsString("JSON object")));
        }
    }

    @Test
    void limitsTheSizeOfJsonBodies() throws Exception {
        var longestEscaped = "\\ud83d\\ude00".repeat(5000);
        expectCreated(jsonCreateRequest(alice(), "{\"status\": \"" + longestEscaped + "\"}"));

        var tooLarge = "{\"status\": \"" + "a".repeat(1024 * 1024) + "\"}";
        mockMvc.perform(jsonCreateRequest(alice(), tooLarge))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error").value("Request body is too large"));
    }

    @Test
    void checksTheScopeBeforeReadingAJsonBody() throws Exception {
        var readOnly = token("alice", "read");
        for (var body : List.of("{\"status\": ", "a".repeat(1024 * 1024))) {
            mockMvc.perform(jsonCreateRequest(readOnly, body)).andExpect(status().isForbidden());
        }
    }

    @Test
    void limitsHowMuchTextUrlsCanAddToAStatus() throws Exception {
        var url = "https://a.example/" + "&".repeat(2000);
        mockMvc.perform(createRequest(alice(), (url + " ").repeat(208)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void acceptsMissingValuesInJson() throws Exception {
        mockMvc.perform(jsonCreateRequest(alice(), """
                        {"status": "Hello", "visibility": null, "sensitive": null, "spoiler_text": null,
                         "language": "", "in_reply_to_id": null}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("public"));
    }

    @Test
    void rejectsFeaturesThatDoNotExistYet() throws Exception {
        for (var body : List.of(
                "{\"status\": \"Hello\", \"scheduled_at\": \"2030-01-01T00:00:00Z\"}",
                "{\"status\": \"Hello\", \"media_ids\": [\"1\"]}",
                "{\"status\": \"Hello\", \"poll\": {\"options\": [\"a\", \"b\"], \"expires_in\": 60}}")) {
            mockMvc.perform(jsonCreateRequest(alice(), body)).andExpect(status().isUnprocessableContent());
        }
        for (var field : List.of("media_ids[]", "poll[options][]", "scheduled_at")) {
            mockMvc.perform(createRequest(alice(), "Hello").param(field, "x"))
                    .andExpect(status().isUnprocessableContent());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM status").query(Long.class).single()).isZero();
    }

    // Reading

    @Test
    void readsPublicStatusesWithoutAToken() throws Exception {
        var id = create(alice(), "Hello");
        mockMvc.perform(get("/api/v1/statuses/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.text").doesNotExist());
    }

    @Test
    void onlyLinksToStatusPagesThatExist() throws Exception {
        for (var visibility : List.of("public", "unlisted")) {
            mockMvc.perform(createRequest(alice(), "Text").param("visibility", visibility))
                    .andExpect(jsonPath("$.url").value(containsString("https://elysium.example/users/alice/statuses/")));
        }
        for (var visibility : List.of("private", "direct")) {
            mockMvc.perform(createRequest(alice(), "Text").param("visibility", visibility))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value(nullValue()));
        }
    }

    @Test
    void hidesPrivateAndDirectStatusesFromEveryoneButTheirAuthor() throws Exception {
        for (var visibility : List.of("private", "direct")) {
            var id = create(alice(), "Secret", visibility);
            var path = "/api/v1/statuses/" + id;
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Record not found"));
            mockMvc.perform(withToken(get(path), bob())).andExpect(status().isNotFound());
            mockMvc.perform(withToken(get(path), appToken())).andExpect(status().isNotFound());
            mockMvc.perform(withToken(get(path), alice())).andExpect(status().isOk());
        }
    }

    @Test
    void malformedAndUnknownIdsAreNotFound() throws Exception {
        for (var id : List.of("nope", "1-1-1-1-1", UUID.randomUUID().toString())) {
            mockMvc.perform(get("/api/v1/statuses/" + id)).andExpect(status().isNotFound());
            mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), alice())).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/accounts/" + id + "/statuses")).andExpect(status().isNotFound());
        }
    }

    // Deleting

    @Test
    void deletesAndReturnsTheSourceText() throws Exception {
        var id = create(alice(), "See https://a.example & more");
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.text").value("See https://a.example & more"));
        mockMvc.perform(get("/api/v1/statuses/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void deleteReportsTheRepliesCount() throws Exception {
        var parent = create(alice(), "Parent");
        create(bob(), "Reply", "public", parent);
        create(bob(), "Another reply", "unlisted", parent);
        create(bob(), "Hidden reply", "private", parent);
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + parent), alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replies_count").value(2));
    }

    @Test
    void cannotDeleteAnotherUsersStatus() throws Exception {
        var id = create(alice(), "Hello");
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), bob())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/statuses/" + id)).andExpect(status().isOk());
        assertThat(tombstones(id)).isZero();
        assertThat(deletedEvents(id)).isZero();
    }

    @Test
    void deletingTwiceOnlyHappensOnce() throws Exception {
        var id = create(alice(), "Hello");
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), alice())).andExpect(status().isOk());
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), alice())).andExpect(status().isNotFound());
        assertThat(tombstones(id)).isEqualTo(1);
        assertThat(deletedEvents(id)).isEqualTo(1);
    }

    private long tombstones(String id) {
        return jdbc.sql("SELECT count(*) FROM status_tombstone WHERE status_id = :id::uuid")
                .param("id", id)
                .query(Long.class)
                .single();
    }

    /**
     * Counts what was published rather than what's stored, because an event's row is gone once its listeners finish.
     */
    private long deletedEvents(String id) {
        return events.stream(StatusDeleted.class).filter(event -> event.statusId().toString().equals(id)).count();
    }

    // Authorization

    @Test
    void anInvalidTokenIsRejectedEvenOnPublicPaths() throws Exception {
        var id = create(alice(), "Hello");
        for (var path : List.of("/api/v1/statuses/" + id, TIMELINE, "/api/v1/instance",
                "/api/v1/accounts/" + aliceId() + "/statuses")) {
            mockMvc.perform(withToken(get(path), "not-a-token")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void readingWithATokenRequiresTheReadScope() throws Exception {
        var id = create(alice(), "Hello");
        var writeOnly = token("alice", "write");
        for (var path : List.of("/api/v1/statuses/" + id, TIMELINE, "/api/v1/accounts/" + aliceId() + "/statuses")) {
            mockMvc.perform(withToken(get(path), writeOnly))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("This action is outside the authorized scopes"));
            mockMvc.perform(withToken(get(path), token("alice", "read:statuses"))).andExpect(status().isOk());
        }
    }

    @Test
    void writingRequiresAWriteScopedUserToken() throws Exception {
        var readOnly = token("alice", "read");
        mockMvc.perform(createRequest(readOnly, "Hello")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/statuses")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Hello"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(createRequest(appToken(), "Hello"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("This method requires an authenticated user"));

        var id = create(alice(), "Hello");
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), readOnly)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/statuses/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + id), appToken()))
                .andExpect(status().isUnprocessableContent());
    }

    // Replies

    @Test
    void repliesRecordTheirParentAndAreCounted() throws Exception {
        var parent = create(alice(), "Parent");
        var reply = create(bob(), "Reply", "public", parent);

        mockMvc.perform(get("/api/v1/statuses/" + reply))
                .andExpect(jsonPath("$.in_reply_to_id").value(parent))
                .andExpect(jsonPath("$.in_reply_to_account_id").value(aliceId()));
        mockMvc.perform(get("/api/v1/statuses/" + parent)).andExpect(jsonPath("$.replies_count").value(1));

        create(bob(), "Hidden reply", "private", parent);
        mockMvc.perform(get("/api/v1/statuses/" + parent)).andExpect(jsonPath("$.replies_count").value(1));
    }

    @Test
    void cannotReplyToAStatusTheAuthorCannotSee() throws Exception {
        var hidden = create(alice(), "Secret", "private");
        for (var parent : List.of(hidden, UUID.randomUUID().toString(), "nope")) {
            mockMvc.perform(createRequest(bob(), "Reply").param("in_reply_to_id", parent))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Record not found"));
        }
        expectCreated(createRequest(alice(), "Own reply").param("in_reply_to_id", hidden));
    }

    @Test
    void aReplyStaysAReplyAfterItsParentIsDeleted() throws Exception {
        var parent = create(alice(), "Parent");
        var reply = create(bob(), "Reply", "public", parent);
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + parent), alice())).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/statuses/" + reply))
                .andExpect(jsonPath("$.in_reply_to_id").value(nullValue()))
                .andExpect(jsonPath("$.in_reply_to_account_id").value(aliceId()));
        mockMvc.perform(get("/api/v1/accounts/" + bobId() + "/statuses").param("exclude_replies", "true"))
                .andExpect(jsonPath("$", empty()));
    }

    @Test
    void aReplyStaysAReplyAfterTheParentsAuthorIsDeleted() throws Exception {
        var parent = create(token("carol", "read write"), "Parent");
        var reply = create(bob(), "Reply", "public", parent);
        jdbc.sql("DELETE FROM actor WHERE username = 'carol'").update();

        mockMvc.perform(get("/api/v1/statuses/" + reply))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.in_reply_to_id").value(nullValue()))
                .andExpect(jsonPath("$.in_reply_to_account_id").value(nullValue()));
        mockMvc.perform(get(TIMELINE)).andExpect(jsonPath("$", empty()));
        mockMvc.perform(get("/api/v1/accounts/" + bobId() + "/statuses").param("exclude_replies", "true"))
                .andExpect(jsonPath("$", empty()));
        mockMvc.perform(get("/api/v1/accounts/" + bobId() + "/statuses")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void leavesOutStatusesWhoseAuthorCannotBeLoaded() throws Exception {
        var orphan = create(token("dave", "read write"), "Orphan");
        var kept = create(bob(), "Kept");
        var older = create(token("dave", "read write"), "Older orphan");
        jdbc.sql("DELETE FROM local_account WHERE actor_id = (SELECT id FROM actor WHERE username = 'dave')").update();

        expectIds(get(TIMELINE), kept);
        mockMvc.perform(get("/api/v1/statuses/" + orphan)).andExpect(status().isNotFound());
        // The page is full before statuses are left out, so it still links to whatever is older.
        mockMvc.perform(get(TIMELINE).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()))
                .andExpect(header().string("Link", containsString("max_id=" + older)));
    }

    // Lists

    @Test
    void publicTimelineOnlyShowsPublicStatusesAndNoRepliesToOthers() throws Exception {
        var visible = create(alice(), "Public");
        create(alice(), "Unlisted", "unlisted");
        create(alice(), "Private", "private");
        create(alice(), "Direct", "direct");
        create(bob(), "Reply to someone else", "public", visible);
        var selfReply = create(alice(), "Reply to self", "public", visible);

        mockMvc.perform(get(TIMELINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(selfReply))
                .andExpect(jsonPath("$[1].id").value(visible));
    }

    @Test
    void publicTimelineHasNothingForRemoteOrMediaFilters() throws Exception {
        create(alice(), "Public");
        mockMvc.perform(get(TIMELINE).param("local", "true")).andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get(TIMELINE).param("remote", "true")).andExpect(jsonPath("$", empty()));
        mockMvc.perform(get(TIMELINE).param("only_media", "1")).andExpect(jsonPath("$", empty()));
        mockMvc.perform(get(TIMELINE).param("only_media", "false")).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void accountStatusesRespectTheViewer() throws Exception {
        create(alice(), "Public");
        create(alice(), "Unlisted", "unlisted");
        create(alice(), "Private", "private");
        create(alice(), "Direct", "direct");
        var path = "/api/v1/accounts/" + aliceId() + "/statuses";

        mockMvc.perform(get(path)).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(withToken(get(path), bob())).andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(withToken(get(path), alice())).andExpect(jsonPath("$", hasSize(4)));
        mockMvc.perform(get(path).param("only_media", "true")).andExpect(jsonPath("$", empty()));
        mockMvc.perform(get(path).param("pinned", "true")).andExpect(jsonPath("$", empty()));
        mockMvc.perform(get(path).param("tagged", "x")).andExpect(jsonPath("$", empty()));
    }

    @Test
    void paginatesNewestFirst() throws Exception {
        var first = create(alice(), "1");
        var second = create(alice(), "2");
        var third = create(alice(), "3");
        var fourth = create(alice(), "4");

        expectIds(get(TIMELINE), fourth, third, second, first);
        expectIds(get(TIMELINE).param("limit", "2"), fourth, third);
        expectIds(get(TIMELINE).param("max_id", third), second, first);
        expectIds(get(TIMELINE).param("since_id", second), fourth, third);
        expectIds(get(TIMELINE).param("since_id", first).param("limit", "2"), fourth, third);
        // min_id returns the statuses closest to it, but still newest first.
        expectIds(get(TIMELINE).param("min_id", first).param("limit", "2"), third, second);
        expectIds(get(TIMELINE).param("min_id", first).param("since_id", third).param("limit", "1"), second);
    }

    @Test
    void clampsTheLimitAndRejectsMalformedValues() throws Exception {
        for (var i = 0; i < 41; i++) {
            jdbc.sql("""
                            INSERT INTO status (id, uri, actor_id, text, content, visibility, published_at)
                            VALUES (:id, :uri, :actorId, 'x', '<p>x</p>', 'public', now())
                            """)
                    .param("id", UUID.randomUUID())
                    .param("uri", "https://elysium.example/" + UUID.randomUUID())
                    .param("actorId", UUID.fromString(aliceId()))
                    .update();
        }
        mockMvc.perform(get(TIMELINE)).andExpect(jsonPath("$", hasSize(20)));
        mockMvc.perform(get(TIMELINE).param("limit", "1000")).andExpect(jsonPath("$", hasSize(40)));
        mockMvc.perform(get(TIMELINE).param("limit", "0")).andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get(TIMELINE).param("limit", "many")).andExpect(status().isUnprocessableContent());
        for (var cursor : List.of("max_id", "since_id", "min_id")) {
            mockMvc.perform(get(TIMELINE).param(cursor, "nope"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(containsString(cursor)));
        }
    }

    @Test
    void linksToTheNeighbouringPages() throws Exception {
        create(alice(), "1");
        var second = create(alice(), "2");
        var third = create(alice(), "3");

        mockMvc.perform(get(TIMELINE).param("limit", "2").param("local", "true").param("ignored", "x"))
                .andExpect(header().string("Link",
                        "<https://elysium.example/api/v1/timelines/public?limit=2&local=true&max_id=%s>; rel=\"next\", "
                                .formatted(second)
                                + "<https://elysium.example/api/v1/timelines/public?limit=2&local=true&min_id=%s>; rel=\"prev\""
                                .formatted(third)));

        // A page that isn't full has no older statuses after it, and an empty page has nothing to link from.
        mockMvc.perform(get(TIMELINE))
                .andExpect(header().string("Link", not(containsString("rel=\"next\""))))
                .andExpect(header().string("Link", containsString("rel=\"prev\"")));
        mockMvc.perform(get(TIMELINE).param("min_id", third)).andExpect(header().doesNotExist("Link"));

        mockMvc.perform(get("/api/v1/accounts/" + aliceId() + "/statuses").param("exclude_replies", "true"))
                .andExpect(header().string("Link", containsString(
                        "/api/v1/accounts/" + aliceId() + "/statuses?limit=20&exclude_replies=true&min_id=" + third)));
    }

    @Test
    void browsersCanReadLinkAndSendIdempotencyKey() throws Exception {
        create(alice(), "Hello");
        mockMvc.perform(get(TIMELINE).header("Origin", "https://app.example"))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Link")));
        mockMvc.perform(options("/api/v1/statuses")
                        .header("Origin", "https://app.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Idempotency-Key")));
    }

    // Accounts and the instance

    @Test
    void verifyCredentialsReportsStatusStatsWithoutDirectStatuses() throws Exception {
        mockMvc.perform(withToken(get(VERIFY_CREDENTIALS), alice()))
                .andExpect(jsonPath("$.statuses_count").value(0))
                .andExpect(jsonPath("$.last_status_at").value(nullValue()));

        create(alice(), "Direct", "direct");
        mockMvc.perform(withToken(get(VERIFY_CREDENTIALS), alice()))
                .andExpect(jsonPath("$.statuses_count").value(0))
                .andExpect(jsonPath("$.last_status_at").value(nullValue()));

        create(alice(), "Public");
        create(alice(), "Private", "private");
        mockMvc.perform(withToken(get(VERIFY_CREDENTIALS), alice()))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.source.privacy").value("public"))
                .andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.statuses_count").value(2))
                .andExpect(jsonPath("$.last_status_at").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}")));
    }

    @Test
    void statusStatsFollowDeletions() throws Exception {
        var first = create(alice(), "First");
        jdbc.sql("UPDATE status SET published_at = '2020-01-02T00:00:00Z' WHERE id = :id::uuid").param("id", first).update();
        var second = create(alice(), "Second");
        create(alice(), "Direct", "direct");
        create(bob(), "Other account");
        expectStatusStats(3, 2, not(nullValue()));

        mockMvc.perform(withToken(delete("/api/v1/statuses/" + second), alice())).andExpect(status().isOk());
        expectStatusStats(2, 1, equalTo("2020-01-02"));

        mockMvc.perform(withToken(delete("/api/v1/statuses/" + first), alice())).andExpect(status().isOk());
        expectStatusStats(1, 0, nullValue());
    }

    @Test
    void deletingAnAccountRemovesItsStatusesFromTheInstanceCount() throws Exception {
        create(bob(), "Kept");
        var erin = token("erin", "read write");
        create(erin, "One");
        create(erin, "Two");
        mockMvc.perform(get("/api/v1/instance")).andExpect(jsonPath("$.stats.status_count").value(3));

        jdbc.sql("DELETE FROM actor WHERE username = 'erin'").update();
        mockMvc.perform(get("/api/v1/instance")).andExpect(jsonPath("$.stats.status_count").value(1));
    }

    @Test
    void instanceAdvertisesTheLengthLimitWithoutAToken() throws Exception {
        alice();
        create(alice(), "Public");
        create(alice(), "Direct", "direct");
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uri").value("elysium.example"))
                .andExpect(jsonPath("$.title").value("elysium.example"))
                .andExpect(jsonPath("$.version").value("4.0.0 (compatible; Elysium)"))
                .andExpect(jsonPath("$.urls.streaming_api").value("wss://elysium.example"))
                .andExpect(jsonPath("$.max_toot_chars").value(5000))
                .andExpect(jsonPath("$.configuration.statuses.max_characters").value(5000))
                .andExpect(jsonPath("$.configuration.statuses.max_media_attachments").value(0))
                .andExpect(jsonPath("$.configuration.statuses.characters_reserved_per_url").value(23))
                .andExpect(jsonPath("$.configuration.media_attachments.supported_mime_types", empty()))
                .andExpect(jsonPath("$.configuration.polls.max_options").value(0))
                .andExpect(jsonPath("$.configuration.accounts.max_featured_tags").value(0))
                .andExpect(jsonPath("$.stats.status_count").value(1))
                .andExpect(jsonPath("$.stats.domain_count").value(0))
                .andExpect(jsonPath("$.registrations").value(true))
                .andExpect(jsonPath("$.contact_account").value(nullValue()))
                .andExpect(jsonPath("$.rules", empty()));
    }

    // Helpers

    private String alice() throws Exception {
        return token("alice", "read write");
    }

    private String bob() throws Exception {
        return token("bob", "read write");
    }

    private String aliceId() throws Exception {
        return actorId("alice");
    }

    private String bobId() throws Exception {
        return actorId("bob");
    }

    private void expectStatusStats(int instanceCount, int aliceCount, Matcher<Object> aliceLastStatusAt) throws Exception {
        mockMvc.perform(get("/api/v1/instance")).andExpect(jsonPath("$.stats.status_count").value(instanceCount));
        mockMvc.perform(withToken(get(VERIFY_CREDENTIALS), alice()))
                .andExpect(jsonPath("$.statuses_count").value(aliceCount))
                .andExpect(jsonPath("$.last_status_at").value(aliceLastStatusAt));
    }

    private String actorId(String username) throws Exception {
        token(username, "read write");
        return jdbc.sql("SELECT id FROM actor WHERE username = :username")
                .param("username", username)
                .query(String.class)
                .single();
    }

    private String create(String token, String text) throws Exception {
        return create(token, text, "public");
    }

    private String create(String token, String text, String visibility) throws Exception {
        return expectCreated(createRequest(token, text).param("visibility", visibility));
    }

    private String create(String token, String text, String visibility, String parent) throws Exception {
        return expectCreated(createRequest(token, text).param("visibility", visibility).param("in_reply_to_id", parent));
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

    private MockHttpServletRequestBuilder jsonCreateRequest(String token, String body) {
        return withToken(post("/api/v1/statuses"), token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void expectIds(MockHttpServletRequestBuilder request, String... ids) throws Exception {
        var body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> found = JsonPath.read(body, "$[*].id");
        assertThat(found).containsExactly(ids);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String token(String username, String scope) throws Exception {
        var cached = TOKENS.get(username + " " + scope);
        if (cached != null) {
            return cached;
        }
        if (TOKENS.keySet().stream().noneMatch(key -> key.startsWith(username + " "))) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }

        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(username + " " + scope, token);
        return token;
    }

    private String appToken() throws Exception {
        var cached = TOKENS.get("app");
        if (cached != null) {
            return cached;
        }
        var app = TestOAuth.registerApp(mockMvc, "read write");
        var token = accessToken(post("/oauth/token")
                .param("grant_type", "client_credentials")
                .param("scope", "read write")
                .param("client_id", app.get("client_id"))
                .param("client_secret", app.get("client_secret")));
        TOKENS.put("app", token);
        return token;
    }

    private String accessToken(MockHttpServletRequestBuilder request) throws Exception {
        var body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }
}
