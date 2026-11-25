package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://elysium.example",
        "elysium.registration.mode=open"
})
@AutoConfigureMockMvc
class LocalAccountIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void aNewAccountCanLogIn() throws Exception {
        signUp("newuser", PASSWORD, PASSWORD)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?registered"));

        assertThat(actors.findLocalByUsername("newuser")).isPresent();
        mockMvc.perform(post("/login").with(csrf()).param("username", "newuser").param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }

    @Test
    void signUpNeedsACsrfToken() throws Exception {
        mockMvc.perform(post("/signup")
                        .param("username", "nocsrf")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isForbidden());
        assertThat(actors.findLocalByUsername("nocsrf")).isEmpty();
    }

    @Test
    void usernamesAreUniqueRegardlessOfCase() throws Exception {
        signUp("taken", PASSWORD, PASSWORD).andExpect(status().is3xxRedirection());

        signUp("TAKEN", PASSWORD, PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("taken")));
    }

    @Test
    void rejectsInvalidSignUps() throws Exception {
        signUp("not-valid", PASSWORD, PASSWORD).andExpect(status().isUnprocessableContent());
        signUp("a".repeat(31), PASSWORD, PASSWORD).andExpect(status().isUnprocessableContent());
        signUp("shortpass", "1234567", "1234567").andExpect(status().isUnprocessableContent());
        signUp("longpass", "x".repeat(129), "x".repeat(129)).andExpect(status().isUnprocessableContent());
        signUp("mismatch", PASSWORD, PASSWORD + "!").andExpect(status().isUnprocessableContent());

        for (var username : new String[]{"not-valid", "shortpass", "longpass", "mismatch"}) {
            assertThat(actors.findLocalByUsername(username)).isEmpty();
        }
    }

    @Test
    void acceptsPasswordsAtTheLengthLimits() throws Exception {
        signUp("minpass", "x".repeat(8), "x".repeat(8)).andExpect(status().is3xxRedirection());
        signUp("maxpass", "x".repeat(128), "x".repeat(128)).andExpect(status().is3xxRedirection());
    }

    @Test
    void escapesTheSubmittedUsernameWhenShowingErrors() throws Exception {
        signUp("<script>", PASSWORD, PASSWORD)
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>"))));
    }

    @Test
    void aFailureAfterCreatingAnActorAlsoUndoesTheActor() {
        assertThatThrownBy(() -> actors.createLocalIfAbsent("rolledback", actor -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(actors.findLocalByUsername("rolledback")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions signUp(
            String username, String password, String confirmPassword) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("invite", TestAccounts.invite(jdbc))
                .param("username", username)
                .param("password", password)
                .param("confirmPassword", confirmPassword));
    }
}
