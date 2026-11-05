package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.RemoteActorProfile;
import dev.biribiri.elysium.auth.AccountActions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "elysium.jobs.worker-enabled=false")
class AccountCommandsTests {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Autowired
    private ActorService actors;
    @Autowired
    private AccountActions accounts;
    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void suspendsAndUnsuspendsALocalAccount() {
        var name = account();

        assertThat(suspend(name)).isZero();
        assertThat(actors.findLocalByUsername(name)).isEmpty();
        assertThat(actors.findAnyLocalByUsername(name).orElseThrow().isSuspended()).isTrue();

        assertThat(unsuspend(name)).isZero();
        assertThat(actors.findLocalByUsername(name)).isPresent();
    }

    @Test
    void suspendsARemoteAccountByItsUri() {
        var domain = "r" + UUID.randomUUID() + ".example";
        var uri = URI.create("https://" + domain + "/users/bob");
        actors.upsertRemote(new RemoteActorProfile(uri, "bob", domain, URI.create(uri + "/inbox"), null, null, null));

        assertThat(suspend(uri.toString())).isZero();

        assertThat(actors.findByUri(uri).orElseThrow().isSuspended()).isTrue();
    }

    @Test
    void reportsAnAccountThatDoesNotExist() {
        assertThat(suspend("nobodyhere")).isOne();
        assertThat(err.toString()).contains("There's no account named nobodyhere");
    }

    @Test
    void reportsUnsuspendingAnAccountThatIsNotSuspended() {
        assertThat(unsuspend(account())).isOne();
        assertThat(err.toString()).contains("isn't suspended");
    }

    @Test
    void deletesWithoutAskingWhenToldTo() {
        var name = account();

        assertThat(new CommandLine(wired(new DeleteAccountCommand()))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(name, "--yes")).isZero();

        assertThat(actors.findAnyLocalByUsername(name).orElseThrow().isDeleted()).isTrue();
        assertThat(new CommandLine(wired(new DeleteAccountCommand()))
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(name, "--yes")).isOne();
    }

    @Test
    void refusesTheLastAdmin() {
        var name = account();
        var id = actors.findLocalByUsername(name).orElseThrow().id();
        jdbc.sql("UPDATE account_credential SET role = 'USER' WHERE actor_id <> :id").param("id", id).update();
        jdbc.sql("""
                        INSERT INTO account_credential (actor_id, password_hash, role) VALUES (:id, 'unused', 'ADMIN')
                        ON CONFLICT (actor_id) DO UPDATE SET role = 'ADMIN'
                        """)
                .param("id", id)
                .update();

        assertThat(suspend(name)).isOne();

        assertThat(err.toString()).contains("last admin");
        assertThat(actors.findLocalByUsername(name)).isPresent();
    }

    private int suspend(String account) {
        var command = new SuspendAccountCommand();
        command.wire(actors, accounts);
        return new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(account);
    }

    private int unsuspend(String account) {
        var command = new UnsuspendAccountCommand();
        command.wire(actors, accounts);
        return new CommandLine(command).setOut(new PrintWriter(out)).setErr(new PrintWriter(err)).execute(account);
    }

    private DeleteAccountCommand wired(DeleteAccountCommand command) {
        command.wire(actors, accounts);
        return command;
    }

    private String account() {
        var name = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        actors.createLocalIfAbsent(name, actor -> {
        });
        return name;
    }
}
