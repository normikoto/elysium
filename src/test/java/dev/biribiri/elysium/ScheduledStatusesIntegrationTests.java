package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.ScheduledStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scheduled statuses are posted by a recurring job, which is off here, so that tests decide when it runs by calling
 * {@link ScheduledStatusService#publishDue}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://scheduled.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class ScheduledStatusesIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String PATH = "/api/v1/scheduled_statuses";

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
    private ScheduledStatusService scheduled;
    @Autowired
    private MediaAttachments attachments;

    @BeforeEach
    void setUp() {
        TestAccounts.makeSureAnAccountExists(actors);
        jdbc.sql("DELETE FROM scheduled_status").update();
        jdbc.sql("DELETE FROM status").update();
    }

    // Scheduling

    @Test
    void schedulesFromJsonWithoutPosting() throws Exception {
        var at = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "Later", "visibility": "unlisted", "spoiler_text": "CW", "language": "en",
                                 "scheduled_at": "%s",
                                 "poll": {"options": ["Yes", "No"], "expires_in": 3600}}
                                """.formatted(at)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.scheduled_at").value(containsString(at.toString().substring(0, 19))))
                .andExpect(jsonPath("$.params.text").value("Later"))
                .andExpect(jsonPath("$.params.visibility").value("unlisted"))
                .andExpect(jsonPath("$.params.spoiler_text").value("CW"))
                .andExpect(jsonPath("$.params.language").value("en"))
                .andExpect(jsonPath("$.params.poll.options[1]").value("No"))
                .andExpect(jsonPath("$.params.poll.expires_in").value("3600"))
                .andExpect(jsonPath("$.params.media_ids").value(nullValue()))
                .andExpect(jsonPath("$.media_attachments", hasSize(0)));
        assertThat(count("SELECT count(*) FROM status")).isZero();
    }

    @Test
    void schedulesFromAFormWithAnOffset() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Later")
                        .param("scheduled_at", "2099-06-01T09:00:00+02:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled_at").value("2099-06-01T07:00:00.000Z"))
                .andExpect(jsonPath("$.params.visibility").value("public"));
    }

    @Test
    void refusesATimeThatIsTooSoonOrUnreadable() throws Exception {
        mockMvc.perform(schedule("alice", "Soon", Instant.now().plus(Duration.ofMinutes(4)).toString()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(schedule("alice", "Past", "2000-01-01T00:00:00Z"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(schedule("alice", "Bad", "tomorrow"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"Number\", \"scheduled_at\": 1234}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(count("SELECT count(*) FROM scheduled_status")).isZero();
        assertThat(count("SELECT count(*) FROM status")).isZero();
    }

    @Test
    void checksTheStatusWhenItIsScheduled() throws Exception {
        mockMvc.perform(schedule("alice", "", later()))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(schedule("alice", "Reply", later()).param("in_reply_to_id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM scheduled_status")).isZero();
    }

    @Test
    void limitsHowManyCanBeScheduledForADay() throws Exception {
        var day = Instant.parse("2098-03-04T00:00:00Z");
        for (var i = 0; i < ScheduledStatusService.DAILY_LIMIT; i++) {
            mockMvc.perform(schedule("dana", "Post " + i, day.plus(Duration.ofMinutes(i)).toString()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(schedule("dana", "One too many", day.plus(Duration.ofHours(23)).toString()))
                .andExpect(status().isUnprocessableContent());
        var nextDay = mockMvc.perform(schedule("dana", "The next day", day.plus(Duration.ofDays(1)).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Moving one to the full day is refused, though moving one within it isn't.
        mockMvc.perform(reschedule("dana", JsonPath.read(nextDay, "$.id"), day.plus(Duration.ofHours(1)).toString()))
                .andExpect(status().isUnprocessableContent());
        var first = jdbc.sql("SELECT id FROM scheduled_status WHERE scheduled_at = :at")
                .param("at", java.sql.Timestamp.from(day)).query(UUID.class).single();
        mockMvc.perform(reschedule("dana", first.toString(), day.plus(Duration.ofHours(2)).toString()))
                .andExpect(status().isOk());
    }

    @Test
    void limitsHowManyCanBeScheduledInAll() throws Exception {
        mockMvc.perform(schedule("erin", "First", later())).andExpect(status().isOk());
        var erin = actors.findLocalByUsername("erin").orElseThrow().id();
        jdbc.sql("""
                        INSERT INTO scheduled_status (id, actor_id, scheduled_at, params)
                        SELECT gen_random_uuid(), :actor, now() + make_interval(days => 400 + n), '{}'
                        FROM generate_series(2, :total) AS n
                        """)
                .param("actor", erin)
                .param("total", ScheduledStatusService.TOTAL_LIMIT)
                .update();
        mockMvc.perform(schedule("erin", "One too many", later()))
                .andExpect(status().isUnprocessableContent());
    }

    // Listing, changing, and cancelling

    @Test
    void listsChangesAndCancelsOnlyTheUsersOwn() throws Exception {
        var first = scheduleId("alice", "First", later());
        var second = scheduleId("alice", "Second", later());
        mockMvc.perform(withToken(get(PATH), "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains(second, first)));
        mockMvc.perform(withToken(get(PATH).param("limit", "1"), "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(header().string("Link", containsString("max_id=" + second)));
        mockMvc.perform(withToken(get(PATH).param("max_id", second), "alice"))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains(first)));
        mockMvc.perform(withToken(get(PATH + "/" + first), "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.text").value("First"));

        mockMvc.perform(withToken(get(PATH), "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get(PATH + "/" + first), "bob")).andExpect(status().isNotFound());
        mockMvc.perform(reschedule("bob", first, later())).andExpect(status().isNotFound());
        mockMvc.perform(withToken(delete(PATH + "/" + first), "bob")).andExpect(status().isNotFound());
        mockMvc.perform(withToken(get(PATH + "/not-an-id"), "alice")).andExpect(status().isNotFound());

        mockMvc.perform(reschedule("alice", first, "2097-01-01T12:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled_at").value("2097-01-01T12:00:00.000Z"));
        mockMvc.perform(withToken(put(PATH + "/" + first), "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduled_at\": \"2097-01-02T12:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled_at").value("2097-01-02T12:00:00.000Z"));
        mockMvc.perform(withToken(put(PATH + "/" + first), "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(withToken(delete(PATH + "/" + first), "alice"))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(content.getResponse().getContentAsString()).isEqualTo("{}"));
        mockMvc.perform(withToken(get(PATH + "/" + first), "alice")).andExpect(status().isNotFound());
    }

    @Test
    void needsTheRightScopes() throws Exception {
        var id = scheduleId("alice", "Scoped", later());
        mockMvc.perform(get(PATH).header("Authorization", "Bearer " + token("alice", "write")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(PATH + "/" + id).header("Authorization", "Bearer " + token("alice", "read")))
                .andExpect(status().isForbidden());
    }

    // Media

    @Test
    void holdsMediaUntilItIsCancelled() throws Exception {
        var media = uploadId("alice");
        mockMvc.perform(schedule("alice", "With media", later()).param("media_ids[]", media))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.params.media_ids[0]").value(media))
                .andExpect(jsonPath("$.media_attachments[0].id").value(media));
        var id = jdbc.sql("SELECT id FROM scheduled_status").query(UUID.class).single().toString();

        // It can't be posted another way, deleted, or removed as an upload that wasn't posted in time.
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Now").param("media_ids[]", media))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(delete("/api/v1/media/" + media), "alice"))
                .andExpect(status().isUnprocessableContent());
        attachments.removeOrphans();
        assertThat(count("SELECT count(*) FROM media_attachment WHERE id = '" + media + "'")).isEqualTo(1);

        mockMvc.perform(withToken(delete(PATH + "/" + id), "alice")).andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Now").param("media_ids[]", media))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.media_attachments[0].id").value(media));
    }

    // Posting

    @Test
    void postsWhenDueAndOnlyOnce() throws Exception {
        var media = uploadId("alice");
        var id = scheduleId("alice", "Posted later", later(), media);
        assertThat(scheduled.publishDue()).isZero();
        assertThat(count("SELECT count(*) FROM status")).isZero();

        makeDue(id);
        scheduled.publishDue();
        scheduled.publishDue();

        assertThat(count("SELECT count(*) FROM status WHERE text = 'Posted later'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scheduled_status")).isZero();
        var statusId = jdbc.sql("SELECT id FROM status").query(UUID.class).single();
        mockMvc.perform(withToken(get("/api/v1/statuses/" + statusId), "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("<p>Posted later</p>"))
                .andExpect(jsonPath("$.media_attachments[0].id").value(media));
        mockMvc.perform(withToken(get(PATH + "/" + id), "alice")).andExpect(status().isNotFound());
    }

    @Test
    void dropsOneThatCanNoLongerBePosted() throws Exception {
        var parent = JsonPath.<String>read(mockMvc.perform(withToken(post("/api/v1/statuses"), "bob")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Parent"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        var media = uploadId("alice");
        var id = scheduleId("alice", "Reply", later(), media, "in_reply_to_id", parent);
        mockMvc.perform(withToken(delete("/api/v1/statuses/" + parent), "bob")).andExpect(status().isOk());

        makeDue(id);
        scheduled.publishDue();

        assertThat(count("SELECT count(*) FROM status WHERE text = 'Reply'")).isZero();
        assertThat(count("SELECT count(*) FROM scheduled_status")).isZero();
        // Its media can be posted again.
        mockMvc.perform(withToken(post("/api/v1/statuses"), "alice")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("status", "Again").param("media_ids[]", media))
                .andExpect(status().isOk());
    }

    @Test
    void doesNotPostOneMovedLaterOrCancelled() throws Exception {
        var moved = scheduleId("alice", "Moved", later());
        var cancelled = scheduleId("alice", "Cancelled", later());
        makeDue(cancelled);
        mockMvc.perform(withToken(delete(PATH + "/" + cancelled), "alice")).andExpect(status().isOk());
        scheduled.publishDue();
        assertThat(count("SELECT count(*) FROM status")).isZero();
        assertThat(count("SELECT count(*) FROM scheduled_status WHERE id = '" + moved + "'")).isEqualTo(1);
    }

    // Helpers

    private void makeDue(String id) {
        jdbc.sql("UPDATE scheduled_status SET scheduled_at = now() - interval '1 second' WHERE id = :id")
                .param("id", UUID.fromString(id))
                .update();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String later() {
        return Instant.now().plus(Duration.ofDays(1)).toString();
    }

    private MockHttpServletRequestBuilder schedule(String username, String text, String at) throws Exception {
        return withToken(post("/api/v1/statuses"), username)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("status", text)
                .param("scheduled_at", at);
    }

    private MockHttpServletRequestBuilder reschedule(String username, String id, String at) throws Exception {
        return withToken(put(PATH + "/" + id), username)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("scheduled_at", at);
    }

    /**
     * @param more the media ID, then names and values of other parameters
     */
    private String scheduleId(String username, String text, String at, String... more) throws Exception {
        var request = schedule(username, text, at);
        if (more.length > 0) {
            request.param("media_ids[]", more[0]);
        }
        for (var i = 1; i + 1 < more.length; i += 2) {
            request.param(more[i], more[i + 1]);
        }
        return JsonPath.read(mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String uploadId(String username) throws Exception {
        var file = new MockMultipartFile("file", "image.png", "image/png", TestImages.png(32, 32));
        return JsonPath.read(mockMvc.perform(withToken(multipart("/api/v2/media").file(file), username))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private <T extends AbstractMockHttpServletRequestBuilder<T>> T withToken(T request, String username)
            throws Exception {
        return request.header("Authorization", "Bearer " + token(username, "read write"));
    }

    private String token(String username, String scope) throws Exception {
        var cached = TOKENS.get(username + " " + scope);
        if (cached != null) {
            return cached;
        }
        if (TOKENS.keySet().stream().noneMatch(key -> key.startsWith(username + " "))) {
            mockMvc.perform(post("/signup").with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", username)
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD))
                    .andExpect(status().is3xxRedirection());
        }
        var token = TestOAuth.userToken(mockMvc, username, PASSWORD, scope);
        TOKENS.put(username + " " + scope, token);
        return token;
    }
}
