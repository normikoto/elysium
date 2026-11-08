package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.RecordingMailer;
import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-ups without an invite, in the open mode, where confirming an email address is all it takes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://signups.example",
        "elysium.registration.mode=open"
})
@AutoConfigureMockMvc
class SignUpIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private RecordingMailer mailer;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void theFormAsksForAnEmailAddress() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(not(containsString("name=\"reason\""))));
    }

    @Test
    void anAccountOnlyExistsOnceItsAddressIsConfirmed() throws Exception {
        signUp("confirmer", "confirmer@mail.example")
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("confirmer@mail.example")));

        assertThat(actors.findAnyLocalByUsername("confirmer")).isEmpty();
        mockMvc.perform(post("/login").with(csrf()).param("username", "confirmer").param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));

        var message = mailer.sentTo("confirmer@mail.example").getLast();
        assertThat(message.body()).contains("@confirmer", "https://signups.example/signup/verify?token=");
        var token = mailer.lastTokenSentTo("confirmer@mail.example");

        confirm(token).andExpect(redirectedUrl("/login?registered"));

        var actor = actors.findLocalByUsername("confirmer").orElseThrow();
        assertThat(accounts.emailsOf(List.of(actor.id()))).containsValue("confirmer@mail.example");
        assertThat(accounts.isAdmin(actor.id())).isFalse();
        mockMvc.perform(post("/login").with(csrf()).param("username", "confirmer").param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
        assertThat(pendingCount("confirmer")).isZero();
    }

    @Test
    void openingTheLinkOnlyAsksForConfirmation() throws Exception {
        signUp("prefetched", "prefetched@mail.example").andExpect(status().isOk());
        var token = mailer.lastTokenSentTo("prefetched@mail.example");

        mockMvc.perform(get("/signup/verify").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@prefetched")))
                .andExpect(content().string(containsString("<form")));

        assertThat(actors.findAnyLocalByUsername("prefetched")).isEmpty();
        assertThat(pendingCount("prefetched")).isOne();
    }

    @Test
    void aLinkOnlyWorksOnce() throws Exception {
        signUp("once", "once@mail.example").andExpect(status().isOk());
        var token = mailer.lastTokenSentTo("once@mail.example");

        confirm(token).andExpect(redirectedUrl("/login?registered"));
        confirm(token).andExpect(status().isNotFound());
        mockMvc.perform(get("/signup/verify").param("token", token)).andExpect(status().isNotFound());
    }

    @Test
    void anUnknownLinkIsRefused() throws Exception {
        confirm("not-a-real-token").andExpect(status().isNotFound());
        mockMvc.perform(get("/signup/verify")).andExpect(status().isNotFound());
    }

    @Test
    void anExpiredLinkStopsWorkingAndFreesTheUsername() throws Exception {
        signUp("expiring", "expiring@mail.example").andExpect(status().isOk());
        var token = mailer.lastTokenSentTo("expiring@mail.example");
        jdbc.sql("UPDATE pending_sign_up SET created_at = now() - interval '25 hours' WHERE username = 'expiring'").update();

        mockMvc.perform(get("/signup/verify").param("token", token)).andExpect(status().isNotFound());
        confirm(token).andExpect(status().isNotFound());
        assertThat(actors.findAnyLocalByUsername("expiring")).isEmpty();

        signUp("expiring", "expiring@mail.example").andExpect(status().isOk());
        assertThat(pendingCount("expiring")).isOne();
    }

    @Test
    void aPendingSignUpHoldsItsUsernameAndAddress() throws Exception {
        signUp("holder", "holder@mail.example").andExpect(status().isOk());

        signUp("HOLDER", "other@mail.example")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("username is taken")));
        signUp("someoneelse", "Holder@Mail.Example")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("email address is already in use")));
        assertThat(accounts.createAccount("holder", PASSWORD)).isEmpty();
        assertThat(accounts.signUp("holder", PASSWORD)).isFalse();
    }

    @Test
    void anAccountsAddressCantBeUsedAgain() throws Exception {
        signUp("firstowner", "owned@mail.example").andExpect(status().isOk());
        confirm(mailer.lastTokenSentTo("owned@mail.example")).andExpect(redirectedUrl("/login?registered"));

        signUp("secondowner", "OWNED@mail.example")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("email address is already in use")));
    }

    @Test
    void anAccountsUsernameCantBeAskedFor() throws Exception {
        assertThat(accounts.signUp("existinguser", PASSWORD)).isTrue();

        signUp("ExistingUser", "existinguser@mail.example")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("username is taken")));
        assertThat(mailer.sentTo("existinguser@mail.example")).isEmpty();
    }

    @Test
    void refusesAnInvalidAddress() throws Exception {
        for (var email : new String[]{"", "nobody", "no@domain", "two@@mail.example", "a@" + "b".repeat(250) + ".example"}) {
            signUp("badaddress", email)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().string(containsString("valid email address")));
        }
        assertThat(pendingCount("badaddress")).isZero();
    }

    @Test
    void anEmailThatCantBeSentLeavesNothingBehind() throws Exception {
        mailer.setFailing(true);
        try {
            signUp("unsent", "unsent@mail.example")
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(containsString("send the email to confirm your address")));
        } finally {
            mailer.setFailing(false);
        }

        assertThat(pendingCount("unsent")).isZero();
        signUp("unsent", "unsent@mail.example").andExpect(status().isOk());
    }

    @Test
    void pendingSignUpsArentSeenAnywhere() throws Exception {
        signUp("invisible", "invisible@mail.example").andExpect(status().isOk());

        mockMvc.perform(get("/.well-known/webfinger").param("resource", "acct:invisible@signups.example"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/users/invisible")).andExpect(status().isNotFound());
    }

    private ResultActions signUp(String username, String email) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("username", username)
                .param("email", email)
                .param("password", PASSWORD)
                .param("confirmPassword", PASSWORD));
    }

    private ResultActions confirm(String token) throws Exception {
        return mockMvc.perform(post("/signup/verify").with(csrf()).param("token", token));
    }

    private long pendingCount(String username) {
        return jdbc.sql("SELECT count(*) FROM pending_sign_up WHERE lower(username) = lower(:username)")
                .param("username", username)
                .query(Long.class)
                .single();
    }
}
