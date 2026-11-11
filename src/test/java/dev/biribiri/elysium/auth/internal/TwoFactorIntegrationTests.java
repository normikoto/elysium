package dev.biribiri.elysium.auth.internal;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TwoFactorIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String NEW_PASSWORD = "another long enough password";
    private static final Pattern RECOVERY_CODE = Pattern.compile("[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private TwoFactorAccounts twoFactor;

    @Autowired
    private AccountModeration moderation;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void aPasswordAloneDoesNotLogIn() throws Exception {
        var username = newUsername();
        enable(account(username));

        var session = passwordStep(username, PASSWORD);

        assertLoggedOut(session);
        mockMvc.perform(get("/login/two-factor").session(session)).andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordDoesNotAskForACode() throws Exception {
        var username = newUsername();
        enable(account(username));

        mockMvc.perform(post("/login").with(csrf()).session(new MockHttpSession())
                        .param("username", username)
                        .param("password", NEW_PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void aCodeFinishesLoggingInOnce() throws Exception {
        var username = newUsername();
        var secret = enable(account(username));
        var code = currentCode(secret);

        var session = passwordStep(username, PASSWORD);
        submitCode(session, code).andExpect(redirectedUrl("/"));

        assertLoggedIn(session);

        var again = passwordStep(username, PASSWORD);
        submitCode(again, code).andExpect(redirectedUrl("/login/two-factor?error"));
        assertLoggedOut(again);
    }

    @Test
    void anOlderCodeThanTheLastOneUsedIsRefused() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var secret = enable(actorId);
        var now = TotpCodes.step(Instant.now());
        assertThat(twoFactor.useStep(actorId, now + 1)).isTrue();

        var session = passwordStep(username, PASSWORD);
        submitCode(session, TotpCodes.code(secret, now)).andExpect(redirectedUrl("/login/two-factor?error"));
    }

    @Test
    void aRecoveryCodeWorksOnceTypedAnyWay() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var codes = enableWithRecoveryCodes(actorId);
        var typed = " " + codes.getFirst().replace("-", "").toLowerCase(Locale.ROOT) + " ";

        var session = passwordStep(username, PASSWORD);
        submitCode(session, typed).andExpect(redirectedUrl("/"));
        assertLoggedIn(session);
        assertThat(twoFactor.recoveryCodesLeft(actorId)).isEqualTo(TwoFactorAccounts.RECOVERY_CODE_COUNT - 1);

        var again = passwordStep(username, PASSWORD);
        submitCode(again, codes.getFirst()).andExpect(redirectedUrl("/login/two-factor?error"));
    }

    @Test
    void runningOutOfAttemptsMeansStartingAgain() throws Exception {
        var username = newUsername();
        var secret = enable(account(username));
        var session = passwordStep(username, PASSWORD);

        for (int i = 0; i < 4; i++) {
            submitCode(session, "000000x").andExpect(redirectedUrl("/login/two-factor?error"));
        }
        submitCode(session, "000000x").andExpect(redirectedUrl("/login?error"));

        submitCode(session, currentCode(secret)).andExpect(redirectedUrl("/login?error"));
        assertLoggedOut(session);
        mockMvc.perform(get("/login/two-factor").session(session)).andExpect(redirectedUrl("/login"));
    }

    @Test
    void aCodeWithoutAPasswordIsRefused() throws Exception {
        var username = newUsername();
        var secret = enable(account(username));
        var session = new MockHttpSession();

        submitCode(session, currentCode(secret)).andExpect(redirectedUrl("/login?error"));
        mockMvc.perform(get("/login/two-factor").session(session)).andExpect(redirectedUrl("/login"));
        assertLoggedOut(session);
    }

    @Test
    void resettingThePasswordEndsALoginWaitingForACode() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var secret = enable(actorId);
        var session = passwordStep(username, PASSWORD);

        accounts.resetPassword(actorId, NEW_PASSWORD);

        submitCode(session, currentCode(secret)).andExpect(redirectedUrl("/login?error"));
        assertLoggedOut(session);
    }

    @Test
    void suspendingTheAccountEndsALoginWaitingForACode() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var secret = enable(actorId);
        var session = passwordStep(username, PASSWORD);

        jdbc.sql("UPDATE actor SET suspended_at = now() WHERE id = :id").param("id", actorId).update();

        submitCode(session, currentCode(secret)).andExpect(redirectedUrl("/login?error"));
        assertLoggedOut(session);
    }

    @Test
    void anAuthorizationContinuesAfterTheCode() throws Exception {
        var username = newUsername();
        var secret = enable(account(username));
        var app = TestOAuth.registerApp(mockMvc, "read");
        var session = new MockHttpSession();

        mockMvc.perform(get("/oauth/authorize").session(session)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", app.get("client_id"))
                        .queryParam("redirect_uri", "https://app.example/callback")
                        .queryParam("scope", "read"))
                .andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/login/two-factor"));
        var resumed = submitCode(session, currentCode(secret))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(resumed).contains("/oauth/authorize").contains(app.get("client_id"));
    }

    @Test
    void settingUpNeedsTheRightPasswordAndCode() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);

        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).session(session).param("password", NEW_PASSWORD))
                .andExpect(status().isUnprocessableContent());
        assertThat(twoFactor.pendingSecret(actorId)).isEmpty();

        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).session(session).param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("<svg")));
        var secret = twoFactor.pendingSecret(actorId).orElseThrow();

        mockMvc.perform(post("/settings/two-factor/confirm").with(csrf()).session(session)
                        .param("code", TotpCodes.code(secret, TotpCodes.step(Instant.now()) + 5)))
                .andExpect(status().isUnprocessableContent());
        assertThat(twoFactor.isEnabled(actorId)).isFalse();

        var page = mockMvc.perform(post("/settings/two-factor/confirm").with(csrf()).session(session)
                        .param("code", currentCode(secret)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        assertThat(twoFactor.isEnabled(actorId)).isTrue();
        assertThat(RECOVERY_CODE.matcher(page).results().count()).isEqualTo(TwoFactorAccounts.RECOVERY_CODE_COUNT);
        assertThat(twoFactor.recoveryCodesLeft(actorId)).isEqualTo(TwoFactorAccounts.RECOVERY_CODE_COUNT);
    }

    @Test
    void turningItOnEndsOtherSessionsButKeepsThisOne() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var other = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).session(session).param("password", PASSWORD))
                .andExpect(status().isOk());
        var secret = twoFactor.pendingSecret(actorId).orElseThrow();

        var result = mockMvc.perform(post("/settings/two-factor/confirm").with(csrf()).session(session)
                        .param("code", currentCode(secret)))
                .andExpect(status().isOk())
                .andReturn();

        assertLoggedOut(other);
        var newSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(newSession).isNotNull();
        assertLoggedIn(newSession);
    }

    @Test
    void theCodeThatTurnsItOnCannotAlsoLogIn() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).session(session).param("password", PASSWORD))
                .andExpect(status().isOk());
        var code = currentCode(twoFactor.pendingSecret(actorId).orElseThrow());
        mockMvc.perform(post("/settings/two-factor/confirm").with(csrf()).session(session).param("code", code))
                .andExpect(status().isOk());

        submitCode(passwordStep(username, PASSWORD), code).andExpect(redirectedUrl("/login/two-factor?error"));
    }

    @Test
    void settingUpAgainIsRefusedOnceItsOn() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var secret = enable(actorId);
        var session = loggedIn(username, secret);

        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).session(session).param("password", PASSWORD))
                .andExpect(status().isConflict());

        assertThat(twoFactor.enabledSecret(actorId)).hasValue(secret);
    }

    @Test
    void turningItOffNeedsThePassword() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var secret = enable(actorId);
        var session = loggedIn(username, secret);

        mockMvc.perform(post("/settings/two-factor/disable").with(csrf()).session(session).param("password", NEW_PASSWORD))
                .andExpect(status().isUnprocessableContent());
        assertThat(twoFactor.isEnabled(actorId)).isTrue();

        mockMvc.perform(post("/settings/two-factor/disable").with(csrf()).session(session).param("password", PASSWORD))
                .andExpect(redirectedUrl("/settings/two-factor?disabled"));

        assertThat(twoFactor.isEnabled(actorId)).isFalse();
        assertThat(twoFactor.recoveryCodesLeft(actorId)).isZero();
        assertLoggedIn(TestOAuth.logIn(mockMvc, username, PASSWORD));
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        var old = enableWithRecoveryCodes(actorId);
        var session = loggedIn(username, twoFactor.enabledSecret(actorId).orElseThrow());

        var page = mockMvc.perform(post("/settings/two-factor/recovery-codes").with(csrf()).session(session)
                        .param("password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();

        var codes = RECOVERY_CODE.matcher(page).results().map(match -> match.group()).toList();
        assertThat(codes).hasSize(TwoFactorAccounts.RECOVERY_CODE_COUNT).doesNotContainAnyElementsOf(old);
        assertThat(twoFactor.useRecoveryCode(actorId, old.getFirst())).isFalse();
        assertThat(twoFactor.useRecoveryCode(actorId, codes.getFirst())).isTrue();
    }

    @Test
    void theSettingsPageNeedsALogin() throws Exception {
        mockMvc.perform(get("/settings/two-factor").accept(MediaType.TEXT_HTML)).andExpect(redirectedUrl("/login"));
        mockMvc.perform(post("/settings/two-factor/setup").with(csrf()).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void deletingTheAccountRemovesItsRecoveryCodes() throws Exception {
        var actorId = account(newUsername());
        enableWithRecoveryCodes(actorId);

        assertThat(moderation.deleteOwn(actorId, PASSWORD)).isTrue();

        assertThat(jdbc.sql("SELECT count(*) FROM account_recovery_code WHERE actor_id = :id")
                .param("id", actorId)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void theCommandTurnsItOff() throws Exception {
        var username = newUsername();
        var actorId = account(username);
        enable(actorId);
        var out = new StringWriter();
        var err = new StringWriter();

        assertThat(runDisable(username, out, err)).isZero();
        assertThat(out.toString()).contains("Turned off");
        assertThat(twoFactor.isEnabled(actorId)).isFalse();
        assertLoggedIn(TestOAuth.logIn(mockMvc, username, PASSWORD));

        assertThat(runDisable(username, out, err)).isZero();
        assertThat(out.toString()).contains("doesn't have two-factor login");
        assertThat(runDisable("nobody" + username, out, err)).isOne();
        assertThat(err.toString()).contains("no local account");
    }

    private int runDisable(String username, StringWriter out, StringWriter err) {
        var command = new DisableTwoFactorCommand();
        command.wire(actors, twoFactor);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(username);
    }

    private MockHttpSession passwordStep(String username, String password) throws Exception {
        var session = new MockHttpSession();
        mockMvc.perform(post("/login").with(csrf()).session(session)
                        .param("username", username)
                        .param("password", password))
                .andExpect(redirectedUrl("/login/two-factor"));
        return session;
    }

    private ResultActions submitCode(MockHttpSession session, String code)
            throws Exception {
        return mockMvc.perform(post("/login/two-factor").with(csrf()).session(session).param("code", code));
    }

    private MockHttpSession loggedIn(String username, byte[] secret) throws Exception {
        var session = passwordStep(username, PASSWORD);
        submitCode(session, currentCode(secret)).andExpect(redirectedUrl("/"));
        return session;
    }

    /**
     * Turns two-factor login on with a code from a while ago, so that the current one hasn't been used yet.
     */
    private byte[] enable(UUID actorId) {
        enableWithRecoveryCodes(actorId);
        return twoFactor.enabledSecret(actorId).orElseThrow();
    }

    private List<String> enableWithRecoveryCodes(UUID actorId) {
        var secret = TotpCodes.newSecret();
        assertThat(twoFactor.startSetup(actorId, secret)).isTrue();
        return twoFactor.confirm(actorId, secret, TotpCodes.step(Instant.now()) - 10).orElseThrow().recoveryCodes();
    }

    private static String currentCode(byte[] secret) {
        return TotpCodes.code(secret, TotpCodes.step(Instant.now()));
    }

    private void assertLoggedIn(MockHttpSession session) throws Exception {
        mockMvc.perform(accountPage().session(session)).andExpect(status().isOk());
    }

    private void assertLoggedOut(MockHttpSession session) throws Exception {
        mockMvc.perform(accountPage().session(session)).andExpect(redirectedUrl("/login"));
    }

    private static MockHttpServletRequestBuilder accountPage() {
        return get("/settings/two-factor").accept(MediaType.TEXT_HTML);
    }

    private UUID account(String username) {
        accounts.createAccount(username, PASSWORD).orElseThrow();
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private static String newUsername() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 25);
    }
}
