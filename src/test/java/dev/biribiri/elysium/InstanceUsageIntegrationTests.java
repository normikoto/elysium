package dev.biribiri.elysium;

import com.jayway.jsonpath.JsonPath;
import dev.biribiri.elysium.actor.ActiveAccounts;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.NewStatus;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The active account counts in NodeInfo and the instance API, and the weekly counts of
 * {@code GET /api/v1/instance/activity}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://usage.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class InstanceUsageIntegrationTests {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String USER = "usageuser";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private StatusService statuses;
    @Autowired
    private ActiveAccounts activeAccounts;

    private static String token;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        if (actors.findLocalByUsername(USER).isEmpty()) {
            signUp(USER);
        }
        userId = actors.findLocalByUsername(USER).orElseThrow().id();
        if (token == null) {
            token = TestOAuth.userToken(mockMvc, USER, PASSWORD, "read");
        }
    }

    /**
     * A new account, since the shared one may already have been recorded today, and recording remembers that.
     */
    @Test
    void anAppUsingATokenRecordsTheAccountsUseOnceADay() throws Exception {
        var username = "usagenew" + suffix();
        signUp(username);
        var id = actors.findLocalByUsername(username).orElseThrow().id();
        var newToken = TestOAuth.userToken(mockMvc, username, PASSWORD, "read");

        useTheApi(newToken);
        useTheApi(newToken);

        assertThat(jdbc.sql("SELECT count(*) FROM account_use_day WHERE actor_id = :id")
                .param("id", id).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT day FROM account_use_day WHERE actor_id = :id")
                .param("id", id).query(LocalDate.class).single()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * The active counts are kept for an hour, so this is the only test that reads them from NodeInfo or the instance.
     */
    @Test
    void nodeInfoCountsActiveAccountsButNotSuspendedOnes() throws Exception {
        useTheApi();
        var suspended = actors.createLocalIfAbsent("usagesuspended" + suffix(), actor -> {
        }).orElseThrow().id();
        jdbc.sql("INSERT INTO account_use_day (actor_id, day) VALUES (:id, current_date)").param("id", suspended).update();
        // Used long enough ago to count for the half year, but not the month.
        var older = actors.createLocalIfAbsent("usageolder" + suffix(), actor -> {
        }).orElseThrow().id();
        jdbc.sql("INSERT INTO account_use_day (actor_id, day) VALUES (:id, current_date - 60)").param("id", older).update();
        var month = activeAccounts.countActiveWithin(ActiveAccounts.MONTH_DAYS);
        var halfYear = activeAccounts.countActiveWithin(ActiveAccounts.HALF_YEAR_DAYS);

        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", suspended).update();

        assertThat(activeAccounts.countActiveWithin(ActiveAccounts.MONTH_DAYS)).isEqualTo(month - 1);
        assertThat(halfYear).isGreaterThan(month);
        for (var version : new String[]{"2.0", "2.1"}) {
            mockMvc.perform(get("/nodeinfo/" + version).accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usage.users.activeMonth").value(month - 1))
                    .andExpect(jsonPath("$.usage.users.activeHalfyear").value(halfYear - 1));
        }
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(jsonPath("$.usage.users.active_month").value(month - 1));
    }

    /**
     * The counts are kept for an hour, so this is the only test that reads them.
     */
    @Test
    void countsTwelveWeeksOfStatusesLoginsAndSignUps() throws Exception {
        useTheApi();
        statuses.create(userId, new NewStatus("Counted", null, null, Visibility.PUBLIC, false, null));
        statuses.create(userId, new NewStatus("Not counted", null, null, Visibility.DIRECT, false, null));
        var monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var since = monday.atStartOfDay().atOffset(ZoneOffset.UTC);
        var expectedStatuses = jdbc.sql("""
                        SELECT count(*) FROM status s JOIN actor a ON a.id = s.actor_id
                        WHERE a.is_local AND a.suspended_at IS NULL AND a.deleted_at IS NULL
                        AND s.visibility <> 'direct' AND s.reblog_of_id IS NULL AND s.published_at >= :since
                        """)
                .param("since", since).query(Long.class).single();
        var expectedLogins = jdbc.sql("""
                        SELECT count(DISTINCT u.actor_id) FROM account_use_day u JOIN actor a ON a.id = u.actor_id
                        WHERE a.suspended_at IS NULL AND a.deleted_at IS NULL AND u.day >= :since
                        """)
                .param("since", monday).query(Long.class).single();
        var expectedRegistrations = jdbc.sql("""
                        SELECT count(*) FROM local_account l JOIN actor a ON a.id = l.actor_id
                        WHERE a.deleted_at IS NULL AND l.created_at >= :since
                        """)
                .param("since", since).query(Long.class).single();

        var body = mockMvc.perform(get("/api/v1/instance/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[0].week").value(Long.toString(since.toEpochSecond())))
                .andExpect(jsonPath("$[0].statuses").value(Long.toString(expectedStatuses)))
                .andExpect(jsonPath("$[0].logins").value(Long.toString(expectedLogins)))
                .andExpect(jsonPath("$[0].registrations").value(Long.toString(expectedRegistrations)))
                .andExpect(jsonPath("$[11].week").value(Long.toString(since.minusWeeks(11).toEpochSecond())))
                .andReturn().getResponse().getContentAsString();

        assertThat(expectedStatuses).isPositive();
        assertThat(expectedLogins).isPositive();
        List<Object> values = JsonPath.read(body, "$[*].*");
        assertThat(values).allMatch(value -> value instanceof String text && text.matches("\\d+"));
    }

    private void signUp(String username) throws Exception {
        mockMvc.perform(post("/signup").with(csrf())
                        .param("invite", TestAccounts.invite(jdbc))
                        .param("username", username)
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().is3xxRedirection());
    }

    private void useTheApi() throws Exception {
        useTheApi(token);
    }

    private void useTheApi(String token) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/verify_credentials").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
