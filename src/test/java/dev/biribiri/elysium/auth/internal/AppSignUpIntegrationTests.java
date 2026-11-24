package dev.biribiri.elysium.auth.internal;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-ups from apps through {@code POST /api/v1/accounts}, in the open mode, where confirming an email address is all it
 * takes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://appsignups.example",
        "elysium.registration.mode=open"
})
@AutoConfigureMockMvc
class AppSignUpIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    private static String appToken;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private Invites invites;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() throws Exception {
        TestAccounts.makeSureAnAccountExists(actors);
        if (appToken == null) {
            appToken = TestOAuth.appToken(mockMvc, "read write");
        }
    }

    @Test
    void theTokenWorksOnceTheAddressIsConfirmed() throws Exception {
        var body = signUp("confirmsfromapp")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("read write"))
                .andExpect(jsonPath("$.created_at").isNumber())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");

        assertThat(actors.findAnyLocalByUsername("confirmsfromapp")).isEmpty();
        verifyCredentials(token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Your login is missing a confirmed e-mail address"));

        mockMvc.perform(post("/signup/verify").with(csrf())
                        .param("token", mailer.lastTokenSentTo("confirmsfromapp@mail.example")))
                .andExpect(status().is3xxRedirection());

        verifyCredentials(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("confirmsfromapp"));
    }

    @Test
    void anInviteCreatesTheAccountStraightAway() throws Exception {
        var invite = invites.create(1, null);
        var body = mockMvc.perform(TestOAuth.signUpRequest(appToken, "invitedfromapp", null, PASSWORD)
                        .param("invite_code", invite.code()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verifyCredentials(JsonPath.read(body, "$.access_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("invitedfromapp"));
        assertThat(mailer.sentTo("invitedfromapp@mail.example")).isEmpty();

        mockMvc.perform(TestOAuth.signUpRequest(appToken, "secondinvited", PASSWORD).param("invite_code", invite.code()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.invite_code[0].error").value("ERR_INVALID"));
        assertThat(actors.findAnyLocalByUsername("secondinvited")).isEmpty();
    }

    @Test
    void jsonBodiesWork() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "jsonsignup", "email": "jsonsignup@mail.example",
                                 "password": "%s", "agreement": true, "locale": "en"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString());
        assertThat(mailer.sentTo("jsonsignup@mail.example")).hasSize(1);
    }

    @Test
    void invalidFieldsAreListedWithMastodonsCodes() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "not valid!")
                        .param("email", "nowhere")
                        .param("password", "short"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value(containsString("Validation failed: Username must contain")))
                .andExpect(jsonPath("$.details.username[0].error").value("ERR_INVALID"))
                .andExpect(jsonPath("$.details.email[0].error").value("ERR_INVALID"))
                .andExpect(jsonPath("$.details.password[0].error").value("ERR_TOO_SHORT"))
                .andExpect(jsonPath("$.details.agreement[0].error").value("ERR_ACCEPTED"));

        mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + appToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.username[0].error").value("ERR_BLANK"))
                .andExpect(jsonPath("$.details.email[0].error").value("ERR_BLANK"))
                .andExpect(jsonPath("$.details.password[0].error").value("ERR_BLANK"));
    }

    @Test
    void takenUsernamesAndAddressesAreRefused() throws Exception {
        signUp("takenfromapp").andExpect(status().isOk());

        signUp("takenfromapp")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("Validation failed: Username has already been taken"))
                .andExpect(jsonPath("$.details.username[0].error").value("ERR_TAKEN"));
        mockMvc.perform(TestOAuth.signUpRequest(appToken, "otherfromapp", "takenfromapp@mail.example", PASSWORD))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.email[0].error").value("ERR_TAKEN"));
    }

    @Test
    void theTokenGoesWithAnExpiredSignUp() throws Exception {
        var body = signUp("expiresfromapp").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.access_token");
        var id = jdbc.sql("SELECT id FROM pending_sign_up WHERE username = 'expiresfromapp'").query(UUID.class).single();

        jdbc.sql("UPDATE pending_sign_up SET created_at = now() - interval '2 days' WHERE id = :id").param("id", id).update();
        // Until the cleanup runs, it's no longer waiting for anything.
        verifyCredentials(token).andExpect(status().isUnauthorized());
        accounts.removeExpiredSignUps();

        assertThat(jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name = :id")
                .param("id", id.toString())
                .query(Long.class)
                .single()).isZero();
        verifyCredentials(token).andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenNeedsWriteAccess() throws Exception {
        mockMvc.perform(TestOAuth.signUpRequest(TestOAuth.appToken(mockMvc, "read"), "readonlyapp", PASSWORD))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "notokenapp"))
                .andExpect(status().isUnauthorized());
        assertThat(mailer.sentTo("readonlyapp@mail.example")).isEmpty();
    }

    @Test
    void theTokenNeverHasAdminScopes() throws Exception {
        var token = TestOAuth.appToken(mockMvc, "read write admin:read");
        mockMvc.perform(TestOAuth.signUpRequest(token, "adminscopes", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(not(containsString("admin"))));
    }

    @Test
    void aFailedEmailLeavesNothingBehind() throws Exception {
        mailer.setFailing(true);
        try {
            signUp("mailfailsapp").andExpect(status().isServiceUnavailable());
        } finally {
            mailer.setFailing(false);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM pending_sign_up WHERE username = 'mailfailsapp'")
                .query(Long.class)
                .single()).isZero();
        signUp("mailfailsapp").andExpect(status().isOk());
    }

    private ResultActions signUp(String username) throws Exception {
        return mockMvc.perform(TestOAuth.signUpRequest(appToken, username, PASSWORD));
    }

    private ResultActions verifyCredentials(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/accounts/verify_credentials").header("Authorization", "Bearer " + token));
    }
}
