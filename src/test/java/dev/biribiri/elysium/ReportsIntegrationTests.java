package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.actor.RemoteActorResolver;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.RemoteStatus;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import dev.biribiri.elysium.moderation.ReportCategory;
import dev.biribiri.elysium.moderation.Reports;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports from local users, and how admins handle them through the API and the admin page, and hear about them. No job
 * worker runs, so a forwarded report is only queued. Reports from other servers are tested in {@code FlagIntegrationTests},
 * and a forwarded one being sent in {@code ReportDeliveryIntegrationTests}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://reports.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class ReportsIntegrationTests {

    private static final String PATH = "/api/v1/reports";
    private static final String ADMIN_PATH = "/api/v1/admin/reports";
    private static final String PAGE = "/admin/reports";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "reportsadmin";
    private static final String USER = "reportsuser";
    private static final String TARGET = "reportstarget";
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
    private Reports reports;
    @MockitoBean
    private RemoteActorResolver resolver;

    private Actor admin;
    private Actor user;
    private Actor target;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM report").update();
        jdbc.sql("DELETE FROM inbox_notification").update();
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        ensureAccount(TARGET);
        admin = actors.findLocalByUsername(ADMIN).orElseThrow();
        user = actors.findLocalByUsername(USER).orElseThrow();
        target = actors.findLocalByUsername(TARGET).orElseThrow();
        TestAccounts.makeAdmin(jdbc, admin.id());
    }

    // Filing

    @Test
    void filesAReportFromAForm() throws Exception {
        var first = publish(target, Visibility.PUBLIC, "First");
        var second = publish(target, Visibility.UNLISTED, "Second");

        mockMvc.perform(withToken(post(PATH), token(USER, "write:reports"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_id", target.id().toString())
                        .param("status_ids[]", first.id().toString())
                        .param("status_ids[]", second.id().toString())
                        .param("comment", "  Spam, all of it  ")
                        .param("category", "spam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.action_taken").value(false))
                .andExpect(jsonPath("$.action_taken_at").value(nullValue()))
                .andExpect(jsonPath("$.category").value("spam"))
                .andExpect(jsonPath("$.comment").value("Spam, all of it"))
                .andExpect(jsonPath("$.forwarded").value(false))
                .andExpect(jsonPath("$.status_ids", hasSize(2)))
                .andExpect(jsonPath("$.rule_ids").value(nullValue()))
                .andExpect(jsonPath("$.target_account.id").value(target.id().toString()));
    }

    @Test
    void filesAReportFromJson() throws Exception {
        var status = publish(target, Visibility.PUBLIC, "Hello");

        mockMvc.perform(withToken(post(PATH), token(USER, "write:reports"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"account_id": "%s", "status_ids": ["%s"], "forward": true}
                                """.formatted(target.id(), status.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("other"))
                .andExpect(jsonPath("$.comment").value(""))
                .andExpect(jsonPath("$.forwarded").value(false))
                .andExpect(jsonPath("$.status_ids[0]").value(status.id().toString()));
    }

    @Test
    void refusesStatusesTheReporterCantSeeOrThatAreSomeoneElses() throws Exception {
        var hidden = publish(target, Visibility.PRIVATE, "Followers only");
        var someoneElses = publish(admin, Visibility.PUBLIC, "Not the target's");

        for (var id : List.of(hidden.id().toString(), someoneElses.id().toString(), "not-a-uuid")) {
            file(form(target).param("status_ids[]", id)).andExpect(status().isNotFound());
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    void refusesWhatThisServerCantRecord() throws Exception {
        file(form(target).param("category", "violation")).andExpect(status().isUnprocessableContent());
        file(form(target).param("rule_ids[]", "1")).andExpect(status().isUnprocessableContent());
        file(form(target).param("category", "nonsense")).andExpect(status().isUnprocessableContent());
        file(form(target).param("comment", "x".repeat(Reports.MAX_COMMENT_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent());
        file(form(user)).andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post(PATH), token(USER, "write:reports"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post(PATH), token(USER, "write:reports"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());

        assertThat(reportCount()).isZero();
    }

    @Test
    void needsTheReportsScope() throws Exception {
        mockMvc.perform(withToken(post(PATH), token(USER, "read"))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("account_id", target.id().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardsAReportOfARemoteAccountAsTheInstanceActor() throws Exception {
        var remote = remote();
        var status = remoteStatus(remote);

        file(form(remote).param("status_ids[]", status.id().toString()).param("forward", "true")
                .param("comment", "Please look"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forwarded").value(true));

        await().atMost(WAIT).until(() -> !flags().isEmpty());
        var flag = flags().getFirst();
        assertThat(JsonPath.<String>read(flag, "$.actor")).isEqualTo("https://reports.example/actor");
        assertThat(JsonPath.<String>read(flag, "$.content")).isEqualTo("Please look");
        assertThat(JsonPath.<List<String>>read(flag, "$.object"))
                .containsExactly(remote.uri().toString(), status.uri().toString());
        assertThat(flag).doesNotContain(user.uri().toString());
    }

    @Test
    void doesNotForwardUnlessAsked() throws Exception {
        file(form(remote())).andExpect(status().isOk()).andExpect(jsonPath("$.forwarded").value(false));
        file(form(target).param("forward", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.forwarded").value(false));

        // Admins are told by a listener of the same event, so once they have been, forwarding would have happened too.
        await().atMost(WAIT).until(() -> notificationRows(admin) == 2);
        Thread.sleep(200);
        assertThat(flags()).isEmpty();
    }

    // Admins hearing about it

    @Test
    void tellsAdminsWithANotification() throws Exception {
        var report = fileReport();

        await().atMost(WAIT).untilAsserted(() ->
                mockMvc.perform(withToken(get("/api/v1/notifications"), token(ADMIN, "read")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$[0].type").value("admin.report"))
                        .andExpect(jsonPath("$[0].account.id").value(user.id().toString()))
                        .andExpect(jsonPath("$[0].report.id").value(report))
                        .andExpect(jsonPath("$[0].report.target_account.id").value(target.id().toString())));
        mockMvc.perform(withToken(get("/api/v2/notifications"), token(ADMIN, "read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification_groups[0].type").value("admin.report"))
                .andExpect(jsonPath("$.notification_groups[0].report.id").value(report));

        mockMvc.perform(withToken(get("/api/v1/notifications"), token(USER, "read")))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anAdminWhoIsNoLongerOneStopsSeeingReports() throws Exception {
        fileReport();
        await().atMost(WAIT).until(() -> notificationRows(admin) == 1);

        jdbc.sql("UPDATE account_credential SET role = 'USER' WHERE actor_id = :id").param("id", admin.id()).update();
        try {
            mockMvc.perform(withToken(get("/api/v1/notifications"), token(ADMIN, "read")))
                    .andExpect(jsonPath("$", hasSize(0)));
            mockMvc.perform(withToken(get("/api/v1/notifications/unread_count"), token(ADMIN, "read")))
                    .andExpect(jsonPath("$.count").value(0));
        } finally {
            TestAccounts.makeAdmin(jdbc, admin.id());
        }
        mockMvc.perform(withToken(get("/api/v1/notifications"), token(ADMIN, "read")))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // The admin API

    @Test
    void listsOpenReportsWithTheirStatusesEvenPrivateOnes() throws Exception {
        var hidden = publish(target, Visibility.PRIVATE, "Only for followers");
        var report = reports.file(user.id(), target, List.of(hidden), "Look", ReportCategory.OTHER, false);

        mockMvc.perform(withToken(get(ADMIN_PATH), token(ADMIN, "admin:read:reports")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(report.id().toString()))
                .andExpect(jsonPath("$[0].account.id").value(user.id().toString()))
                .andExpect(jsonPath("$[0].target_account.id").value(target.id().toString()))
                .andExpect(jsonPath("$[0].target_account.account.id").value(target.id().toString()))
                .andExpect(jsonPath("$[0].statuses[0].content").value(containsString("Only for followers")))
                .andExpect(jsonPath("$[0].rules", hasSize(0)));
        mockMvc.perform(withToken(get(ADMIN_PATH).param("resolved", "true"), token(ADMIN, "admin:read:reports")))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get(ADMIN_PATH).param("target_account_id", user.id().toString()),
                        token(ADMIN, "admin:read:reports")))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(get(ADMIN_PATH).param("account_id", user.id().toString()),
                        token(ADMIN, "admin:read:reports")))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void handlesAReport() throws Exception {
        var id = fileReport();
        var write = token(ADMIN, "admin:write:reports");

        mockMvc.perform(withToken(post(ADMIN_PATH + "/" + id + "/assign_to_self"), write))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned_account.id").value(admin.id().toString()));
        mockMvc.perform(withToken(post(ADMIN_PATH + "/" + id + "/unassign"), write))
                .andExpect(jsonPath("$.assigned_account").value(nullValue()));
        mockMvc.perform(withToken(put(ADMIN_PATH + "/" + id), write)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\": \"legal\"}"))
                .andExpect(jsonPath("$.category").value("legal"));
        mockMvc.perform(withToken(put(ADMIN_PATH + "/" + id), write)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\": \"violation\"}"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(withToken(post(ADMIN_PATH + "/" + id + "/resolve"), write))
                .andExpect(jsonPath("$.action_taken").value(true))
                .andExpect(jsonPath("$.action_taken_at").isString())
                .andExpect(jsonPath("$.action_taken_by_account.id").value(admin.id().toString()));
        mockMvc.perform(withToken(get(ADMIN_PATH), token(ADMIN, "admin:read:reports")))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(withToken(post(ADMIN_PATH + "/" + id + "/reopen"), write))
                .andExpect(jsonPath("$.action_taken").value(false))
                .andExpect(jsonPath("$.action_taken_by_account").value(nullValue()));
        mockMvc.perform(withToken(get(ADMIN_PATH + "/" + UUID.randomUUID()), token(ADMIN, "admin:read:reports")))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsCanUseTheAdminApi() throws Exception {
        fileReport();

        mockMvc.perform(withToken(get(ADMIN_PATH), token(USER, "admin:read:reports read")))
                .andExpect(status().isForbidden());
    }

    // The admin page

    @Test
    void showsReportsOnTheAdminPageWithPrivateStatuses() throws Exception {
        var hidden = publish(target, Visibility.PRIVATE, "A followers-only secret");
        var report = reports.file(user.id(), target, List.of(hidden), "Have a look",
                ReportCategory.SPAM, false);
        var session = logIn(ADMIN);

        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1 open report")))
                .andExpect(content().string(containsString("Have a look")));
        mockMvc.perform(get(PAGE + "/" + report.id()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A followers-only secret")))
                .andExpect(content().string(containsString("/admin/statuses/" + hidden.id() + "/remove")));

        mockMvc.perform(post(PAGE + "/" + report.id() + "/assign").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE + "/" + report.id()));
        mockMvc.perform(post(PAGE + "/" + report.id() + "/resolve").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(reports.find(report.id()).orElseThrow().isResolved()).isTrue();
        assertThat(reports.find(report.id()).orElseThrow().assignedTo()).isEqualTo(admin.id());
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(content().string(not(containsString("Have a look"))));
        mockMvc.perform(get(PAGE).param("resolved", "").session(session))
                .andExpect(content().string(containsString("Have a look")));
    }

    @Test
    void onlyAdminsCanUseTheAdminPage() throws Exception {
        var id = fileReport();
        var session = logIn(USER);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE + "/" + id + "/resolve").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    // Cleanup

    @Test
    void aDomainBlockRemovesReportsAboutItsAccounts() throws Exception {
        var remote = remote();
        file(form(remote)).andExpect(status().isOk());

        reports.removeForDomain(remote.domain());

        assertThat(reportCount()).isZero();
    }

    private String fileReport() throws Exception {
        var status = publish(target, Visibility.PUBLIC, "Reported");
        var body = file(form(target).param("status_ids[]", status.id().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private MockHttpServletRequestBuilder form(Actor account) throws Exception {
        return withToken(post(PATH), token(USER, "write:reports"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("account_id", account.id().toString());
    }

    private ResultActions file(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request);
    }

    private Status publish(Actor author, Visibility visibility, String text) {
        return statuses.create(author.id(), new NewStatus(text, null, null, visibility, false, null));
    }

    private Actor remote() {
        var host = "reports-" + UUID.randomUUID() + ".example";
        var uri = URI.create("https://" + host + "/users/r");
        return actors.upsertRemote(
                new RemoteActorProfile(uri, "r", host, URI.create(uri + "/inbox"), null, null, null, null));
    }

    private Status remoteStatus(Actor author) {
        var id = UUID.randomUUID();
        var stored = statuses.storeRemote(new RemoteStatus(URI.create(author.uri() + "/notes/" + id),
                URI.create("https://" + author.domain() + "/@r/" + id), author.id(), "<p>From afar</p>", null, null,
                Visibility.PUBLIC, false, null, Instant.now())).orElseThrow();
        return statuses.findForModeration(stored.toString()).orElseThrow();
    }

    private List<String> flags() {
        return jdbc.sql("SELECT body FROM outbound_activity WHERE actor_id IS NULL AND body LIKE '%\"Flag\"%'")
                .query(String.class)
                .list();
    }

    private long reportCount() {
        return jdbc.sql("SELECT count(*) FROM report").query(Long.class).single();
    }

    private long notificationRows(Actor recipient) {
        return jdbc.sql("SELECT count(*) FROM inbox_notification WHERE recipient_id = :id AND type = 'admin.report'")
                .param("id", recipient.id())
                .query(Long.class)
                .single();
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

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }
}
