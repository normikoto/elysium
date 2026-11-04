package dev.biribiri.elysium.auth.internal;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.instance.base-url=https://elysium.example")
@AutoConfigureMockMvc
class JobAdminIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String ORIGIN = "https://elysium.example";
    private static final String UI = "/admin/db-scheduler";
    private static final String API = "/admin/db-scheduler-api";
    private static final AtomicInteger nextAccount = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LocalAccounts accounts;
    @Autowired
    private ActorService actors;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    // Getting to the page

    @Test
    void anAnonymousVisitorIsSentToLogIn() throws Exception {
        mockMvc.perform(get(UI).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).endsWith("/login"));
    }

    @Test
    void loggingInReturnsToTheAdminPage() throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(get(UI + "/history/all").session(session).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());

        var account = account(AccountRole.ADMIN);
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", account)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .contains(UI + "/history/all"));
    }

    @Test
    void anAdminPageDoesNotReplaceAPendingAuthorization() throws Exception {
        var app = mockMvc.perform(post("/api/v1/apps")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("client_name", "Test")
                        .param("redirect_uris", "https://app.example/callback")
                        .param("scopes", "read"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var session = new MockHttpSession();
        mockMvc.perform(get("/oauth/authorize").session(session).accept(MediaType.TEXT_HTML)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", JsonPath.<String>read(app, "$.client_id"))
                        .queryParam("redirect_uri", "https://app.example/callback")
                        .queryParam("scope", "read"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get(UI).session(session).accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());

        var account = account(AccountRole.USER);
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", account)
                        .param("password", PASSWORD))
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).contains("/oauth/authorize"));
    }

    @ParameterizedTest
    @ValueSource(strings = {API + "/config", UI + "/assets/index.js", UI + "/js/context-path.js"})
    void whatAPageLoadsGetsA403AndIsNotReturnedToAfterLogin(String path) throws Exception {
        // Some of these could be asked for with a broad Accept header, which shouldn't make them count as pages.
        var session = new MockHttpSession();
        mockMvc.perform(get(path).session(session).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());

        var account = account(AccountRole.ADMIN);
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", account)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aRequestThatDoesNotAskForHtmlGetsA403() throws Exception {
        mockMvc.perform(get(UI).accept(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        mockMvc.perform(get(UI)).andExpect(status().isForbidden());
    }

    @Test
    void aRegularUserCannotReachTheUi() throws Exception {
        var session = logIn(account(AccountRole.USER));

        mockMvc.perform(get(UI).session(session).accept(MediaType.TEXT_HTML)).andExpect(status().isForbidden());
        mockMvc.perform(get(API + "/tasks/all").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void anAdminWhoLosesTheRoleLosesAccessImmediately() throws Exception {
        var account = account(AccountRole.ADMIN);
        var session = logIn(account);
        mockMvc.perform(get(API + "/config").session(session)).andExpect(status().isOk());

        accounts.setRole(actors.findLocalByUsername(account).orElseThrow().id(), AccountRole.USER);

        mockMvc.perform(get(API + "/config").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void theUnprefixedPathsAreClosedEvenToAdmins() throws Exception {
        var session = logIn(account(AccountRole.ADMIN));

        mockMvc.perform(get("/db-scheduler").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(get("/db-scheduler-api/config").session(session)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            UI,
            UI + "/history/all",
            UI + "/activity-delivery",
            UI + "/activity-delivery/page/2",
            UI + "/history/activity-delivery/someid"})
    void everyRouteInTheUiCanBeReloaded(String path) throws Exception {
        mockMvc.perform(get(path).session(logIn(account(AccountRole.ADMIN))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<div id=\"root\">")));
    }

    @Test
    void theUiFilesAreStillServedAsFiles() throws Exception {
        mockMvc.perform(get(UI + "/favicon.svg").session(logIn(account(AccountRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/svg+xml"));
    }

    // Seeing jobs

    @Test
    void anAdminCanSeeAJobsDataAndWhyItFailed() throws Exception {
        var job = failedJob();
        var session = logIn(account(AccountRole.ADMIN));

        mockMvc.perform(get(API + "/tasks/all").session(session)
                        .queryParam("filter", "FAILED")
                        .queryParam("size", "100")
                        .queryParam("sorting", "DEFAULT")
                        .queryParam("asc", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("https://elysium.example/activities/" + job)));

        mockMvc.perform(get(API + "/logs/all").session(session)
                        .queryParam("filter", "FAILED")
                        .queryParam("size", "100")
                        .queryParam("sorting", "DEFAULT")
                        .queryParam("asc", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The inbox answered with a 410")));
    }

    // Changing jobs: the UI can't send a CSRF token, so Origin takes its place

    @Test
    void anAdminsOwnPageCanDeleteAJob() throws Exception {
        var job = failedJob();

        mockMvc.perform(post(API + "/tasks/delete").session(logIn(account(AccountRole.ADMIN)))
                        .header("Origin", ORIGIN)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isOk());

        assertThat(jobExists(job)).isFalse();
    }

    @Test
    void anAdminsOwnPageCanRetryAJob() throws Exception {
        var job = failedJob();

        mockMvc.perform(post(API + "/tasks/rerun").session(logIn(account(AccountRole.ADMIN)))
                        .header("Origin", ORIGIN)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery")
                        .queryParam("scheduleTime", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT execution_time < now() + INTERVAL '1 day' FROM scheduled_tasks WHERE task_instance = :id")
                .param("id", job).query(Boolean.class).optional()).hasValue(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example",
            "http://elysium.example",
            "https://elysium.example.evil.example",
            "https://elysium.example:8443",
            "null"})
    void aPageOnAnotherOriginCannotChangeJobs(String origin) throws Exception {
        var job = failedJob();

        mockMvc.perform(post(API + "/tasks/delete").session(logIn(account(AccountRole.ADMIN)))
                        .header("Origin", origin)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isForbidden());

        assertThat(jobExists(job)).isTrue();
    }

    @Test
    void aRequestWithNoOriginNeedsAToken() throws Exception {
        var job = failedJob();
        var session = logIn(account(AccountRole.ADMIN));

        mockMvc.perform(post(API + "/tasks/delete").session(session)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isForbidden());
        assertThat(jobExists(job)).isTrue();

        mockMvc.perform(post(API + "/tasks/delete").session(session).with(csrf())
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isOk());
        assertThat(jobExists(job)).isFalse();
    }

    @Test
    void theOriginOnlyStandsInForATokenOnTheJobApi() throws Exception {
        mockMvc.perform(post("/admin/jobs").session(logIn(account(AccountRole.ADMIN))).header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRightOriginDoesNotLetOthersChangeJobs() throws Exception {
        var job = failedJob();

        mockMvc.perform(post(API + "/tasks/delete").header("Origin", ORIGIN)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(API + "/tasks/delete").session(logIn(account(AccountRole.USER)))
                        .header("Origin", ORIGIN)
                        .queryParam("id", job)
                        .queryParam("name", "activity-delivery"))
                .andExpect(status().isForbidden());

        assertThat(jobExists(job)).isTrue();
    }

    // Helpers

    private String failedJob() {
        var id = UUID.randomUUID().toString();
        var data = """
                {"activityId":"https://elysium.example/activities/%s","inbox":"https://a.test/inbox","queuedAt":"2026-01-01T00:00:00Z"}
                """.formatted(id).strip();
        // Far enough ahead that the worker leaves it alone during the test.
        jdbc.sql("""
                        INSERT INTO scheduled_tasks
                            (task_name, task_instance, task_data, execution_time, picked, version, consecutive_failures, last_failure)
                        VALUES ('activity-delivery', :id, convert_to(:data, 'UTF8'), now() + INTERVAL '30 days', false, 1, 1, now())
                        """)
                .param("id", id)
                .param("data", data)
                .update();
        jdbc.sql("""
                        INSERT INTO scheduled_execution_logs
                            (id, task_name, task_instance, task_data, time_started, time_finished, succeeded, duration_ms,
                             exception_class, exception_message)
                        VALUES (:logId, 'activity-delivery', :id, convert_to(:data, 'UTF8'), now(), now(), false, 1,
                                'PermanentJobFailure', 'The inbox answered with a 410')
                        """)
                .param("logId", UUID.randomUUID().getMostSignificantBits())
                .param("id", id)
                .param("data", data)
                .update();
        return id;
    }

    private boolean jobExists(String id) {
        return jdbc.sql("SELECT count(*) FROM scheduled_tasks WHERE task_instance = :id")
                .param("id", id)
                .query(Long.class)
                .single() > 0;
    }

    private String account(AccountRole role) {
        var username = "jobadmin" + nextAccount.incrementAndGet();
        assertThat(accounts.createAccount(username, PASSWORD)).isPresent();
        accounts.setRole(actors.findLocalByUsername(username).orElseThrow().id(), role);
        return username;
    }

    private MockHttpSession logIn(String username) throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("error"));
        return session;
    }
}
