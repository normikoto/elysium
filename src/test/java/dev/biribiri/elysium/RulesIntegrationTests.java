package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.moderation.Rule;
import dev.biribiri.elysium.moderation.Rules;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server's rules: the admin page that sets them, and where people see them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.jobs.worker-enabled=false"
})
@AutoConfigureMockMvc
class RulesIntegrationTests {

    private static final String PAGE = "/admin/rules";
    private static final String PASSWORD = "correct horse battery staple";
    private static final String ADMIN = "rulesadmin";
    private static final String USER = "rulesuser";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ActorService actors;
    @Autowired
    private Rules rules;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM instance_rule").update();
        TestAccounts.makeSureAnAccountExists(actors);
        ensureAccount(ADMIN);
        ensureAccount(USER);
        TestAccounts.makeAdmin(jdbc, actors.findLocalByUsername(ADMIN).orElseThrow().id());
    }

    // The admin page

    @Test
    void addsEditsAndRemovesARuleOnThePage() throws Exception {
        var session = logIn(ADMIN);

        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "  <b>Be kind</b>  ")
                        .param("hint", "No harassment."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(PAGE));
        var rule = rules.list().getFirst();
        assertThat(rule.text()).isEqualTo("<b>Be kind</b>");
        assertThat(rule.hint()).isEqualTo("No harassment.");
        mockMvc.perform(get(PAGE).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;b&gt;Be kind&lt;/b&gt;")))
                .andExpect(content().string(not(containsString("<b>Be kind</b>"))));

        mockMvc.perform(post(PAGE + "/" + rule.id()).session(session).with(csrf())
                        .param("text", "Be nice")
                        .param("hint", ""))
                .andExpect(redirectedUrl(PAGE));
        assertThat(rules.list()).singleElement().satisfies(edited -> {
            assertThat(edited.text()).isEqualTo("Be nice");
            assertThat(edited.hint()).isEmpty();
        });

        mockMvc.perform(post(PAGE + "/" + rule.id() + "/delete").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void movesRulesUpAndDown() throws Exception {
        var session = logIn(ADMIN);
        var first = rules.create("First", null);
        var second = rules.create("Second", null);
        var third = rules.create("Third", null);

        mockMvc.perform(post(PAGE + "/" + third.id() + "/move").session(session).with(csrf())
                        .param("direction", "up"))
                .andExpect(redirectedUrl(PAGE));
        assertThat(texts()).containsExactly("First", "Third", "Second");

        mockMvc.perform(post(PAGE + "/" + first.id() + "/move").session(session).with(csrf())
                        .param("direction", "down"))
                .andExpect(redirectedUrl(PAGE));
        assertThat(texts()).containsExactly("Third", "First", "Second");

        // Moving past either end does nothing.
        rules.move(third.id(), true);
        rules.move(second.id(), false);
        assertThat(texts()).containsExactly("Third", "First", "Second");
    }

    @Test
    void showsWhyARuleWasRefusedOnThePage() throws Exception {
        var session = logIn(ADMIN);

        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("text", " ").param("hint", "Kept"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("A rule needs some text.")))
                .andExpect(content().string(containsString("Kept")));
        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("text", "x".repeat(Rules.MAX_TEXT_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(post(PAGE).session(session).with(csrf())
                        .param("text", "Fine")
                        .param("hint", "x".repeat(Rules.MAX_HINT_LENGTH + 1)))
                .andExpect(status().isUnprocessableContent());
        assertThat(rules.list()).isEmpty();

        var rule = rules.create("Be kind", null);
        mockMvc.perform(post(PAGE + "/" + rule.id()).session(session).with(csrf()).param("text", ""))
                .andExpect(status().isUnprocessableContent());
        assertThat(texts()).containsExactly("Be kind");
    }

    @Test
    void ignoresRulesThatAreGone() throws Exception {
        var session = logIn(ADMIN);
        var missing = UUID.randomUUID();

        mockMvc.perform(post(PAGE + "/" + missing).session(session).with(csrf()).param("text", "Anything"))
                .andExpect(redirectedUrl(PAGE));
        mockMvc.perform(post(PAGE + "/" + missing + "/move").session(session).with(csrf()).param("direction", "up"))
                .andExpect(redirectedUrl(PAGE));
        mockMvc.perform(post(PAGE + "/not-an-id/delete").session(session).with(csrf()))
                .andExpect(redirectedUrl(PAGE));
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void thePageNeedsACsrfToken() throws Exception {
        mockMvc.perform(post(PAGE).session(logIn(ADMIN)).param("text", "Be kind"))
                .andExpect(status().isForbidden());
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void regularUsersCannotUseThePage() throws Exception {
        var session = logIn(USER);

        mockMvc.perform(get(PAGE).session(session)).andExpect(status().isForbidden());
        mockMvc.perform(post(PAGE).session(session).with(csrf()).param("text", "Be kind"))
                .andExpect(status().isForbidden());
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void anonymousVisitorsAreSentToLogIn() throws Exception {
        mockMvc.perform(get(PAGE).accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection());
    }

    // The API

    @Test
    void listsRulesInOrderWithoutAToken() throws Exception {
        var first = rules.create("Be kind", "No harassment.");
        var second = rules.create("No spam", null);
        rules.move(second.id(), true);

        mockMvc.perform(get("/api/v1/instance/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second.id().toString()))
                .andExpect(jsonPath("$[0].text").value("No spam"))
                .andExpect(jsonPath("$[0].hint").value(""))
                .andExpect(jsonPath("$[1].id").value(first.id().toString()))
                .andExpect(jsonPath("$[1].hint").value("No harassment."));
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[0].text").value("No spam"));
        mockMvc.perform(get("/api/v2/instance"))
                .andExpect(jsonPath("$.rules", hasSize(2)))
                .andExpect(jsonPath("$.rules[1].text").value("Be kind"));
    }

    @Test
    void listsNoRulesWhenThereAreNone() throws Exception {
        mockMvc.perform(get("/api/v1/instance/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // The sign-up page

    @Test
    void theSignUpPageListsTheRules() throws Exception {
        rules.create("Be <i>kind</i>", "No harassment.");

        mockMvc.perform(get("/signup").param("invite", TestAccounts.invite(jdbc)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("you agree to follow these rules")))
                .andExpect(content().string(containsString("Be &lt;i&gt;kind&lt;/i&gt;")))
                .andExpect(content().string(containsString("No harassment.")));
    }

    @Test
    void theSignUpPageLeavesOutRulesWhenThereAreNone() throws Exception {
        mockMvc.perform(get("/signup").param("invite", TestAccounts.invite(jdbc)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("these rules"))));
    }

    // Helpers

    private List<String> texts() {
        return rules.list().stream().map(Rule::text).toList();
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
}
