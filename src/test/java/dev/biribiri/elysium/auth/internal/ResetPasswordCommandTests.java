package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestAccounts;
import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
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
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ResetPasswordCommandTests {

    private static final String OLD_PASSWORD = "the old password";
    private static final String NEW_PASSWORD = "the new password";
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
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void replacesThePassword() {
        var actorId = account("replaced");
        var updatedBefore = updatedAt(actorId);

        assertThat(run(() -> NEW_PASSWORD, "replaced")).isZero();

        assertThat(passwordWorks("replaced", NEW_PASSWORD)).isTrue();
        assertThat(passwordWorks("replaced", OLD_PASSWORD)).isFalse();
        assertThat(updatedAt(actorId)).isAfter(updatedBefore);
        assertThat(out.toString()).contains("logged it out of every browser").doesNotContain(NEW_PASSWORD);
        assertThat(err.toString()).doesNotContain(NEW_PASSWORD);
    }

    @Test
    void findsTheAccountRegardlessOfCase() {
        account("mixedcase");

        assertThat(run(() -> NEW_PASSWORD, "MixedCase")).isZero();

        assertThat(passwordWorks("mixedcase", NEW_PASSWORD)).isTrue();
    }

    @Test
    void givesAnAccountWithoutAPasswordItsFirstOne() {
        actors.createLocalIfAbsent("passwordless", actor -> {
        });

        assertThat(run(() -> NEW_PASSWORD, "passwordless")).isZero();

        assertThat(passwordWorks("passwordless", NEW_PASSWORD)).isTrue();
    }

    @Test
    void revokesOnlyThatAccountsAuthorizationsAndConsent() {
        var actorId = account("revoked");
        var otherActorId = account("unaffected");
        var own = authorization(actorId.toString(), "authorization_code");
        var other = authorization(otherActorId.toString(), "authorization_code");
        var appOnly = authorization("some-client", "client_credentials");
        consent(actorId);
        consent(otherActorId);

        assertThat(run(() -> NEW_PASSWORD, "revoked")).isZero();

        assertThat(authorizationExists(own)).isFalse();
        assertThat(authorizationExists(other)).isTrue();
        assertThat(authorizationExists(appOnly)).isTrue();
        assertThat(consentExists(actorId)).isFalse();
        assertThat(consentExists(otherActorId)).isTrue();
    }

    @Test
    void refusesAnUnknownUsernameWithoutReadingAPassword() {
        assertThat(run(MUST_NOT_READ, "nobody")).isOne();

        assertThat(err.toString()).contains("no local account");
    }

    @Test
    void anInvalidPasswordChangesNothing() {
        var actorId = account("invalid");
        var authorization = authorization(actorId.toString(), "authorization_code");
        consent(actorId);

        assertThat(run(() -> "short", "invalid")).isOne();
        assertThat(run(() -> null, "invalid")).isOne();

        assertThat(err.toString()).contains("8 to 128");
        assertThat(passwordWorks("invalid", OLD_PASSWORD)).isTrue();
        assertThat(authorizationExists(authorization)).isTrue();
        assertThat(consentExists(actorId)).isTrue();
    }

    private UUID account(String username) {
        assertThat(accounts.signUp(username, OLD_PASSWORD)).isTrue();
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private boolean passwordWorks(String username, String password) {
        return passwordEncoder.matches(password, accounts.loadUserByUsername(username).getPassword());
    }

    private OffsetDateTime updatedAt(UUID actorId) {
        return jdbc.sql("SELECT updated_at FROM account_credential WHERE actor_id = :actorId")
                .param("actorId", actorId)
                .query(OffsetDateTime.class)
                .single();
    }

    private String authorization(String principalName, String grantType) {
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO oauth2_authorization
                            (id, registered_client_id, principal_name, authorization_grant_type)
                        VALUES (:id, 'some-client', :principalName, :grantType)
                        """)
                .param("id", id)
                .param("principalName", principalName)
                .param("grantType", grantType)
                .update();
        return id;
    }

    private boolean authorizationExists(String id) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single() == 1;
    }

    private void consent(UUID actorId) {
        jdbc.sql("""
                        INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                        VALUES ('some-client', :principalName, 'SCOPE_read')
                        """)
                .param("principalName", actorId.toString())
                .update();
    }

    private boolean consentExists(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization_consent WHERE principal_name = :principalName")
                .param("principalName", actorId.toString())
                .query(Long.class)
                .single() == 1;
    }

    private int run(PasswordReader passwords, String username) {
        var command = new ResetPasswordCommand();
        command.wire(actors, accounts, passwords);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(username);
    }
}
