package dev.biribiri.elysium;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.internal.PasswordHashingBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each test uses its own addresses and usernames, because the limits' counts last for the whole context.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"elysium.registration.mode=open", "elysium.rate-limit.enabled=true"})
@AutoConfigureMockMvc
class RateLimitIntegrationTests {

    private static final String PASSWORD = "a long enough password";
    private static final String WRONG_PASSWORD = "not the right password";

    private static final AtomicInteger nextAddress = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActorService actors;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void turnsAwayLoginsOverTheLimitWithoutHashing() throws Exception {
        createAccount("blockedlogin");
        var address = newAddress();
        failLogins(5, "blockedlogin", address);
        clearInvocations(passwordEncoder);

        // Even the correct password doesn't work during this time, or guesses could still be tested.
        logIn("blockedlogin", PASSWORD, address)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void anotherAddressCanStillLogIn() throws Exception {
        createAccount("otheraddress");
        failLogins(5, "otheraddress", newAddress());

        logIn("otheraddress", PASSWORD, newAddress())
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void aSuccessfulLoginClearsEarlierFailures() throws Exception {
        createAccount("clearsfailures");
        var address = newAddress();
        failLogins(4, "clearsfailures", address);

        logIn("clearsfailures", PASSWORD, address).andExpect(redirectedUrl("/"));

        failLogins(5, "clearsfailures", address);
        logIn("clearsfailures", PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    @Test
    void successfulLoginsArentLimited() throws Exception {
        createAccount("oftenloggedin");
        var address = newAddress();

        for (var i = 0; i < 25; i++) {
            logIn("oftenloggedin", PASSWORD, address).andExpect(redirectedUrl("/"));
        }
    }

    @Test
    void aBusyServerDoesntUseUpLoginAttempts() throws Exception {
        createAccount("busylimit");
        var address = newAddress();
        doThrow(new PasswordHashingBusyException()).when(passwordEncoder).matches(any(), any());

        for (var i = 0; i < 6; i++) {
            logIn("busylimit", PASSWORD, address).andExpect(redirectedUrl("/login?busy"));
        }

        Mockito.reset(passwordEncoder);
        logIn("busylimit", PASSWORD, address).andExpect(redirectedUrl("/"));
    }

    @Test
    void requestsWithoutACsrfTokenDontUseUpLoginAttempts() throws Exception {
        createAccount("nocsrflimit");
        var address = newAddress();

        for (var i = 0; i < 6; i++) {
            mockMvc.perform(from(address, post("/login"))
                            .param("username", "nocsrflimit")
                            .param("password", WRONG_PASSWORD))
                    .andExpect(status().isForbidden());
        }

        logIn("nocsrflimit", PASSWORD, address).andExpect(redirectedUrl("/"));
    }

    @Test
    void aServerErrorDoesntUseUpLoginAttempts() throws Exception {
        createAccount("errorlimit");
        var address = newAddress();
        doThrow(new IllegalStateException("failed")).when(passwordEncoder).matches(any(), any());

        for (var i = 0; i < 6; i++) {
            assertThatThrownBy(() -> logIn("errorlimit", PASSWORD, address))
                    .isInstanceOf(IllegalStateException.class);
        }

        Mockito.reset(passwordEncoder);
        logIn("errorlimit", PASSWORD, address).andExpect(redirectedUrl("/"));
    }

    @Test
    void spacesAroundAUsernameDontGetAroundItsLimit() throws Exception {
        createAccount("paddedname");
        var address = newAddress();

        // Spring Security trims the username, so this tests the real account's password.
        logIn(" paddedname ", PASSWORD, address).andExpect(redirectedUrl("/"));

        failLogins(3, "paddedname ", address);
        failLogins(2, " paddedname", address);
        logIn(" paddedname ", PASSWORD, address).andExpect(status().isTooManyRequests());
        logIn("paddedname", PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    @Test
    void limitsAnAddressAcrossUsernames() throws Exception {
        var address = newAddress();
        for (var i = 0; i < 20; i++) {
            // Usernames that can't exist count too, because they still cost a hash.
            var username = i % 2 == 0 ? "sprayed" + i : "not-valid-" + i;
            logIn(username, WRONG_PASSWORD, address).andExpect(redirectedUrl("/login?error"));
        }

        logIn("sprayedagain", WRONG_PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    @Test
    void turnsAwaySignUpsOverTheLimit() throws Exception {
        var address = newAddress();
        for (var i = 0; i < 5; i++) {
            signUp("limitedsignup" + i, PASSWORD, address).andExpect(redirectedUrl("/login?registered"));
        }

        signUp("toomanysignups", PASSWORD, address)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().string(containsString("Too many sign-ups")));

        assertThat(actors.findLocalByUsername("toomanysignups")).isEmpty();
    }

    @Test
    void takenUsernamesCountAsSignUpAttempts() throws Exception {
        createAccount("probedname");
        var address = newAddress();
        for (var i = 0; i < 5; i++) {
            signUp("probedname", PASSWORD, address).andExpect(status().isUnprocessableContent());
        }

        signUp("probedname", PASSWORD, address).andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidSignUpsDontCount() throws Exception {
        var address = newAddress();
        for (var i = 0; i < 6; i++) {
            mockMvc.perform(from(address, post("/signup")).with(csrf())
                            .param("invite", TestAccounts.invite(jdbc))
                            .param("username", "mistyped")
                            .param("password", PASSWORD)
                            .param("confirmPassword", PASSWORD + "!"))
                    .andExpect(status().isUnprocessableContent());
        }

        signUp("mistyped", PASSWORD, address).andExpect(redirectedUrl("/login?registered"));
    }

    @Test
    void aBusyServerDoesntUseUpSignUpAttempts() throws Exception {
        var address = newAddress();
        doThrow(new PasswordHashingBusyException()).when(passwordEncoder).encode(any());

        for (var i = 0; i < 6; i++) {
            signUp("busysignup", PASSWORD, address).andExpect(status().isServiceUnavailable());
        }

        Mockito.reset(passwordEncoder);
        signUp("busysignup", PASSWORD, address).andExpect(redirectedUrl("/login?registered"));
    }

    @Test
    void aServerErrorDoesntUseUpSignUpAttempts() throws Exception {
        var address = newAddress();
        doThrow(new IllegalStateException("failed")).when(passwordEncoder).encode(any());

        for (var i = 0; i < 6; i++) {
            assertThatThrownBy(() -> signUp("errorsignup", PASSWORD, address))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        Mockito.reset(passwordEncoder);
        signUp("errorsignup", PASSWORD, address).andExpect(redirectedUrl("/login?registered"));
    }

    @Test
    void wrongPasswordsOnTheDeletePageCountAgainstTheAccount() throws Exception {
        var username = "deletelimit" + nextAddress.incrementAndGet();
        createAccount(username);
        var session = TestOAuth.logIn(mockMvc, username, PASSWORD);
        var address = newAddress();

        for (var i = 0; i < 5; i++) {
            mockMvc.perform(from(address, post("/settings/delete-account")).session(session).with(csrf())
                            .param("password", WRONG_PASSWORD))
                    .andExpect(status().isUnprocessableContent());
        }

        mockMvc.perform(from(address, post("/settings/delete-account")).session(session).with(csrf())
                        .param("password", PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
        logIn(username, PASSWORD, address).andExpect(status().isTooManyRequests());
        assertThat(actors.findLocalByUsername(username)).isPresent();
    }

    private void createAccount(String username) throws Exception {
        signUp(username, PASSWORD, newAddress()).andExpect(redirectedUrl("/login?registered"));
    }

    private void failLogins(int count, String username, String address) throws Exception {
        for (var i = 0; i < count; i++) {
            logIn(username, WRONG_PASSWORD, address).andExpect(redirectedUrl("/login?error"));
        }
    }

    private ResultActions logIn(String username, String password, String address) throws Exception {
        return mockMvc.perform(from(address, post("/login")).with(csrf())
                .param("username", username)
                .param("password", password));
    }

    private ResultActions signUp(String username, String password, String address) throws Exception {
        return mockMvc.perform(from(address, post("/signup")).with(csrf())
                .param("invite", TestAccounts.invite(jdbc))
                .param("username", username)
                .param("password", password)
                .param("confirmPassword", password));
    }

    private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
        return request.with(mock -> {
            mock.setRemoteAddr(address);
            return mock;
        });
    }

    /**
     * An address in a range that's not private, from the block reserved for benchmarking.
     */
    private static String newAddress() {
        var n = nextAddress.incrementAndGet();
        return "198.18." + (n / 256) + "." + (n % 256);
    }
}
