package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.internal.PasswordHashingBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.registration.enabled=true")
@AutoConfigureMockMvc
class LocalAccountIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActorService actors;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void showsTheSignUpForm() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mockMvc.perform(get("/login")).andExpect(content().string(containsString("/signup")));
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

    @Test
    void loginTellsPeopleWhenTheServerIsTooBusy() throws Exception {
        signUp("busylogin", PASSWORD, PASSWORD).andExpect(status().is3xxRedirection());
        doThrow(new PasswordHashingBusyException()).when(passwordEncoder).matches(any(), any());

        mockMvc.perform(post("/login").with(csrf()).param("username", "busylogin").param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?busy"));
        mockMvc.perform(get("/login").param("busy", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The server is busy")));
    }

    @Test
    void signUpTellsPeopleWhenTheServerIsTooBusy() throws Exception {
        doThrow(new PasswordHashingBusyException()).when(passwordEncoder).encode(any());

        signUp("busyuser", PASSWORD, PASSWORD)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("The server is busy")));
        assertThat(actors.findLocalByUsername("busyuser")).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions signUp(
            String username, String password, String confirmPassword) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("username", username)
                .param("password", password)
                .param("confirmPassword", confirmPassword));
    }
}
