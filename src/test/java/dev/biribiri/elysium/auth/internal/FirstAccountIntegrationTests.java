package dev.biribiri.elysium.auth.internal;

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
import org.springframework.transaction.support.TransactionTemplate;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Empties the database before each test. The base URL is only there to give this class its own context, and so its own
 * database, because other classes expect their accounts to stay.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "elysium.instance.base-url=https://first-account.example",
        "elysium.registration.enabled=true"
})
@AutoConfigureMockMvc
class FirstAccountIntegrationTests {

    private static final String PASSWORD = "a long enough password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private ActorService actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void removeAccounts() {
        jdbc.sql("DELETE FROM actor").update();
    }

    @Test
    void theInstanceActorDoesNotCountAsTheFirstAccount() {
        actors.instanceActor();

        assertThat(actors.hasLocalAccounts()).isFalse();
        assertThat(actors.countLocal()).isZero();
        assertThat(accounts.createAccount("first", PASSWORD)).contains(AccountRole.ADMIN);
    }

    @Test
    void theFirstAccountIsAnAdminAndLaterOnesAreNot() {
        assertThat(accounts.createAccount("first", PASSWORD)).contains(AccountRole.ADMIN);
        assertThat(accounts.createAccount("second", PASSWORD)).contains(AccountRole.USER);

        assertThat(authorities("first")).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(authorities("second")).containsExactly("ROLE_USER");
        assertThat(accounts.isAdmin(id("first"))).isTrue();
        assertThat(accounts.isAdmin(id("second"))).isFalse();
    }

    @Test
    void createUserSaysWhenTheAccountBecameAnAdmin() {
        var out = new StringWriter();
        assertThat(runCreateUser("first", out)).isZero();
        assertThat(out.toString()).contains("Created account first").contains("admin");

        var laterOut = new StringWriter();
        assertThat(runCreateUser("second", laterOut)).isZero();
        assertThat(laterOut.toString()).contains("Created account second").doesNotContain("admin");
    }

    @Test
    void aFailedFirstAccountLeavesTheNextOneAsTheFirst() {
        assertThatThrownBy(() -> accounts.createAccount("invalid", "short"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(accounts.createAccount("first", PASSWORD)).contains(AccountRole.ADMIN);
    }

    @Test
    void anAccountWithoutAPasswordStillCountsAsAnAccount() {
        actors.createLocalIfAbsent("passwordless", actor -> {
        });

        assertThat(accounts.createAccount("next", PASSWORD)).contains(AccountRole.USER);
    }

    @Test
    void removingTheLastAdminDoesNotMakeTheNextAccountAnAdmin() {
        accounts.createAccount("first", PASSWORD);
        accounts.setRole(id("first"), AccountRole.USER);

        assertThat(accounts.createAccount("next", PASSWORD)).contains(AccountRole.USER);
        assertThat(accounts.signUp("signedup", PASSWORD)).isTrue();
        assertThat(accounts.isAdmin(id("signedup"))).isFalse();
    }

    // The sign-up page

    @Test
    void theSignUpPageExplainsThatItIsUnavailable() throws Exception {
        mockMvc.perform(get("/signup"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("isn't accepting sign-ups yet")))
                .andExpect(content().string(not(containsString("<form"))));
    }

    @Test
    void theSignUpPageRefusesToCreateTheFirstAccount() throws Exception {
        signUp("stranger")
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("isn't accepting sign-ups yet")))
                .andExpect(content().string(not(containsString("<form"))));

        assertThat(actors.countLocal()).isZero();
    }

    @Test
    void creatingAnAccountRefusesToBeFirstEvenIfTheEarlierCheckWasPassed() {
        assertThatThrownBy(() -> accounts.signUp("stranger", PASSWORD))
                .isInstanceOf(FirstAccountRefusedException.class);

        assertThat(actors.findLocalByUsername("stranger")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM actor").query(Long.class).single()).isZero();
    }

    @Test
    void theSignUpPageWorksOnceAnAccountExists() throws Exception {
        accounts.createAccount("first", PASSWORD);

        mockMvc.perform(get("/signup")).andExpect(status().isOk());
        signUp("second").andExpect(redirectedUrl("/login?registered"));

        assertThat(accounts.isAdmin(id("second"))).isFalse();
    }

    // Accounts created at the same time

    @Test
    void anAccountCreatedWhileTheFirstIsBeingCreatedIsNotAnAdmin() throws Exception {
        var result = whileAnotherAccountIsBeingCreated(() -> accounts.createAccount("second", PASSWORD), true);

        assertThat(result).contains(AccountRole.USER);
        assertThat(accounts.isAdmin(id("first"))).isTrue();
    }

    @Test
    void anAccountBecomesTheFirstIfTheOneBeforeItFails() throws Exception {
        var result = whileAnotherAccountIsBeingCreated(() -> accounts.createAccount("second", PASSWORD), false);

        assertThat(result).contains(AccountRole.ADMIN);
        assertThat(actors.findLocalByUsername("first")).isEmpty();
    }

    @Test
    void aSignUpWaitsForTheFirstAccountAndThenWorks() throws Exception {
        var result = whileAnotherAccountIsBeingCreated(() -> accounts.signUp("second", PASSWORD), true);

        assertThat(result).isTrue();
        assertThat(accounts.isAdmin(id("second"))).isFalse();
    }

    @Test
    void aSignUpIsStillRefusedIfTheFirstAccountFails() {
        assertThatThrownBy(() -> whileAnotherAccountIsBeingCreated(() -> accounts.signUp("second", PASSWORD), false))
                .hasCauseInstanceOf(FirstAccountRefusedException.class);

        assertThat(actors.countLocal()).isZero();
    }

    @Test
    void manyAccountsCreatedAtOnceGiveOneAdmin() throws Exception {
        var tasks = new ArrayList<Callable<Optional<AccountRole>>>();
        for (var i = 0; i < 6; i++) {
            var username = "operator" + i;
            tasks.add(() -> accounts.createAccount(username, PASSWORD));
        }

        var roles = runTogether(tasks);

        assertThat(roles).filteredOn(role -> role.equals(Optional.of(AccountRole.ADMIN))).hasSize(1);
        assertThat(roles).filteredOn(role -> role.equals(Optional.of(AccountRole.USER))).hasSize(5);
        assertThat(admins()).hasSize(1);
    }

    @Test
    void signUpsAtTheSameTimeAsTheFirstAccountNeverBecomeAdmins() throws Exception {
        var tasks = new ArrayList<Callable<Boolean>>();
        for (var i = 0; i < 3; i++) {
            var operator = "operator" + i;
            var stranger = "stranger" + i;
            tasks.add(() -> accounts.createAccount(operator, PASSWORD).isPresent());
            tasks.add(() -> {
                try {
                    return accounts.signUp(stranger, PASSWORD);
                } catch (FirstAccountRefusedException e) {
                    return false;
                }
            });
        }

        runTogether(tasks);

        assertThat(admins()).hasSize(1).allMatch(username -> username.startsWith("operator"));
    }

    /**
     * Runs a task while another transaction has created an account and is holding the lock, and doesn't commit until the
     * task is waiting for that lock. Without the lock, the task wouldn't wait, and couldn't see the uncommitted account,
     * so it would decide that it's creating the first one.
     *
     * @param commit whether the other transaction commits, rather than rolling back
     */
    private <T> T whileAnotherAccountIsBeingCreated(Callable<T> task, boolean commit) throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = new AtomicReference<java.util.concurrent.Future<T>>();
            transaction.executeWithoutResult(status -> {
                assertThat(accounts.createAccount("first", PASSWORD)).contains(AccountRole.ADMIN);
                result.set(executor.submit(task));
                awaitSomethingWaitingForALock();
                if (!commit) {
                    status.setRollbackOnly();
                }
            });
            return result.get().get(30, TimeUnit.SECONDS);
        }
    }

    private void awaitSomethingWaitingForALock() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")
                .query(Long.class)
                .single() == 0) {
            assertThat(System.nanoTime()).as("Nothing waited for the lock").isLessThan(deadline);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            var ready = new CountDownLatch(tasks.size());
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                ready.await();
                return task.call();
            })).toList();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private List<String> admins() {
        return jdbc.sql("""
                        SELECT a.username
                        FROM account_credential c
                        JOIN actor a ON a.id = c.actor_id
                        WHERE c.role = 'ADMIN'
                        """)
                .query(String.class)
                .list();
    }

    private List<String> authorities(String username) {
        return accounts.loadUserByUsername(username).getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
    }

    private java.util.UUID id(String username) {
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private int runCreateUser(String username, StringWriter out) {
        var command = new CreateUserCommand();
        command.wire(actors, accounts, () -> PASSWORD);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(new StringWriter(), true))
                .execute(username);
    }

    private org.springframework.test.web.servlet.ResultActions signUp(String username) throws Exception {
        return mockMvc.perform(post("/signup").with(csrf())
                .param("username", username)
                .param("password", PASSWORD)
                .param("confirmPassword", PASSWORD));
    }
}
