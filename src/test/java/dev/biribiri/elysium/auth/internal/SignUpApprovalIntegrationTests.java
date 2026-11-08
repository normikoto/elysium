package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.RecordingMailer;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestOAuth;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-ups in the approval mode, which wait for an admin once they've confirmed their address.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://approvals.example",
        "elysium.registration.mode=approval"
})
@AutoConfigureMockMvc
class SignUpApprovalIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private SignUps signUps;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void theFormAsksWhyTheyWantToJoin() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"reason\"")));
    }

    @Test
    void theServerSaysThatApprovalIsNeeded() throws Exception {
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(jsonPath("$.registrations").value(true))
                .andExpect(jsonPath("$.approval_required").value(true))
                .andExpect(jsonPath("$.invites_enabled").value(false));
        mockMvc.perform(get("/nodeinfo/2.1")).andExpect(jsonPath("$.openRegistrations").value(true));
    }

    @Test
    void aConfirmedSignUpWaitsForAnAdmin() throws Exception {
        confirmedSignUp("waiting", "I'd like to join");

        assertThat(actors.findAnyLocalByUsername("waiting")).isEmpty();
        mockMvc.perform(post("/login").with(csrf()).param("username", "waiting").param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
        var pending = signUps.findAwaitingApproval("waiting").orElseThrow();
        assertThat(pending.reason()).isEqualTo("I'd like to join");
    }

    @Test
    void anUnconfirmedSignUpIsntListed() throws Exception {
        signUp("unconfirmed", "").andExpect(status().isOk());

        assertThat(signUps.findAwaitingApproval("unconfirmed")).isEmpty();
        assertThat(signUps.approve(pendingId("unconfirmed"))).isFalse();
        assertThat(actors.findAnyLocalByUsername("unconfirmed")).isEmpty();
    }

    @Test
    void anAdminCanApproveFromTheAdminPage() throws Exception {
        confirmedSignUp("approvable", "Because <b>reasons</b>");
        var admin = adminSession();

        mockMvc.perform(get("/admin/sign-ups").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("approvable")))
                .andExpect(content().string(containsString("Because &lt;b&gt;reasons&lt;/b&gt;")));

        var id = signUps.findAwaitingApproval("approvable").orElseThrow().id();
        mockMvc.perform(post("/admin/sign-ups/" + id + "/approve").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/sign-ups"));

        assertThat(actors.findLocalByUsername("approvable")).isPresent();
        assertThat(mailer.sentTo("approvable@mail.example").getLast().body()).contains("has been approved");
        mockMvc.perform(post("/login").with(csrf()).param("username", "approvable").param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/admin/sign-ups/" + id + "/approve").session(admin).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void anAdminCanRejectFromTheAdminPage() throws Exception {
        confirmedSignUp("rejectable", "");
        var admin = adminSession();
        var id = signUps.findAwaitingApproval("rejectable").orElseThrow().id();

        mockMvc.perform(post("/admin/sign-ups/" + id + "/reject").session(admin).with(csrf()))
                .andExpect(redirectedUrl("/admin/sign-ups"));

        assertThat(actors.findAnyLocalByUsername("rejectable")).isEmpty();
        assertThat(signUps.findAwaitingApproval("rejectable")).isEmpty();
        assertThat(mailer.sentTo("rejectable@mail.example").getLast().body()).contains("wasn't approved");
        // The username and address are free again.
        signUp("rejectable", "").andExpect(status().isOk());
    }

    @Test
    void onlyAdminsCanSeeTheAdminPage() throws Exception {
        assertThat(accounts.signUp("notadmin", PASSWORD)).isTrue();
        var session = TestOAuth.logIn(mockMvc, "notadmin", PASSWORD);

        mockMvc.perform(get("/admin/sign-ups").session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/sign-ups/" + UUID.randomUUID() + "/approve").session(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void commandsListApproveAndReject() throws Exception {
        confirmedSignUp("bycommand", "Line one\nLine two");
        confirmedSignUp("rejectedbycommand", "");

        var list = new ListSignUpsCommand();
        list.wire(signUps);
        assertThat(run(list)).isZero();
        assertThat(out.toString()).contains("bycommand  bycommand@mail.example", "    Line one", "    Line two");

        var approve = new ApproveSignUpCommand();
        approve.wire(signUps);
        assertThat(run(approve, "BYCOMMAND")).isZero();
        assertThat(actors.findLocalByUsername("bycommand")).isPresent();
        assertThat(run(approve, "bycommand")).isOne();

        var reject = new RejectSignUpCommand();
        reject.wire(signUps);
        assertThat(run(reject, "rejectedbycommand")).isZero();
        assertThat(signUps.findAwaitingApproval("rejectedbycommand")).isEmpty();
        assertThat(run(reject, "nobodyatall")).isOne();
        assertThat(err.toString()).contains("No sign-up for nobodyatall");
    }

    private void confirmedSignUp(String username, String reason) throws Exception {
        signUp(username, reason).andExpect(status().isOk());
        mockMvc.perform(post("/signup/verify").with(csrf())
                        .param("token", mailer.lastTokenSentTo(username + "@mail.example")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("admin has to approve")));
    }

    private ResultActions signUp(String username, String reason) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("username", username)
                .param("email", username + "@mail.example")
                .param("password", PASSWORD)
                .param("confirmPassword", PASSWORD)
                .param("reason", reason));
    }

    private UUID pendingId(String username) {
        return jdbc.sql("SELECT id FROM pending_sign_up WHERE username = :username")
                .param("username", username)
                .query(UUID.class)
                .single();
    }

    private MockHttpSession adminSession() throws Exception {
        var username = "admin" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(accounts.signUp(username, PASSWORD)).isTrue();
        accounts.setRole(actors.findLocalByUsername(username).orElseThrow().id(), AccountRole.ADMIN);
        return TestOAuth.logIn(mockMvc, username, PASSWORD);
    }

    private int run(Callable<Integer> command, String... args) {
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
