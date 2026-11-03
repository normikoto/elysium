package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CreateUserCommandTests {

    private static final String PASSWORD = "a long enough password";
    private static final PasswordReader MUST_NOT_READ = () -> {
        throw new AssertionError("The password shouldn't have been read");
    };

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InstanceProperties instance;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void createsAnAccountThatCanLogIn() {
        assertThat(run(() -> PASSWORD, "created")).isZero();

        assertThat(out.toString()).contains("Created account created");
        var hash = accounts.loadUserByUsername("created").getPassword();
        assertThat(hash).startsWith("{argon2id}$argon2id$");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void storesThePasswordExactlyAsWritten() {
        var password = "first ${password} #{1+1}";

        assertThat(run(() -> password, "literal")).isZero();

        assertThat(passwordEncoder.matches(password, accounts.loadUserByUsername("literal").getPassword())).isTrue();
    }

    @Test
    void doesNotPrintThePassword() {
        run(() -> PASSWORD, "quiet");
        run(() -> PASSWORD, "quiet");

        assertThat(out.toString()).doesNotContain(PASSWORD);
        assertThat(err.toString()).doesNotContain(PASSWORD);
    }

    @Test
    void refusesATakenUsernameRegardlessOfCase() {
        assertThat(accounts.signUp("existing", PASSWORD)).isTrue();

        assertThat(run(MUST_NOT_READ, "EXISTING")).isOne();

        assertThat(err.toString()).contains("taken");
        assertThat(passwordEncoder.matches(PASSWORD, accounts.loadUserByUsername("existing").getPassword())).isTrue();
    }

    @Test
    void refusesAnInvalidUsernameWithoutReadingAPassword() {
        assertThat(run(MUST_NOT_READ, "not-valid")).isOne();

        assertThat(err.toString()).contains("1 to 30");
        assertThat(actors.findLocalByUsername("not-valid")).isEmpty();
    }

    @Test
    void refusesAnInvalidPasswordAndCreatesNothing() {
        assertThat(run(() -> "short", "shortpass")).isOne();
        assertThat(err.toString()).contains("8 to 128");
        assertThat(actors.findLocalByUsername("shortpass")).isEmpty();

        assertThat(run(() -> null, "nopass")).isOne();
        assertThat(actors.findLocalByUsername("nopass")).isEmpty();
    }

    @Test
    void reportsMismatchedPasswords() {
        assertThat(run(() -> {
            throw new IllegalArgumentException("The passwords don't match");
        }, "mismatch")).isOne();

        assertThat(err.toString()).contains("don't match");
        assertThat(actors.findLocalByUsername("mismatch")).isEmpty();
    }

    @Test
    void reportsFailureWhenANonLocalActorHasTheSameIdentity() {
        var uri = instance.resolve("/users/orphan").toString();
        jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem)
                        VALUES (:id, :uri, 'orphan', :domain, :uri, :uri, 'key')
                        """)
                .param("id", UUID.randomUUID())
                .param("uri", uri)
                .param("domain", instance.domain())
                .update();

        assertThat(run(() -> PASSWORD, "orphan")).isOne();

        assertThat(err.toString()).contains("taken");
        assertThat(actors.findLocalByUsername("orphan")).isEmpty();
    }

    private int run(PasswordReader passwords, String username) {
        var command = new CreateUserCommand();
        command.wire(actors, accounts, passwords);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(username);
    }
}
