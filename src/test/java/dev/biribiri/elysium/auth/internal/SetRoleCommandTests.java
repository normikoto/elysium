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
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SetRoleCommandTests {

    private static final String PASSWORD = "a long enough password";

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Autowired
    private InstanceProperties instance;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void makeSureAnAccountExists() {
        TestAccounts.makeSureAnAccountExists(actors);
    }

    @Test
    void givesAndRemovesTheRole() {
        var actorId = account("promoted");

        assertThat(run("promoted", "admin")).isZero();
        assertThat(out.toString()).contains("promoted is now an admin");
        assertThat(accounts.isAdmin(actorId)).isTrue();

        assertThat(run("promoted", "user")).isZero();
        assertThat(out.toString()).contains("promoted is now a regular user");
        assertThat(accounts.isAdmin(actorId)).isFalse();
    }

    @Test
    void findsTheAccountAndRoleRegardlessOfCase() {
        var actorId = account("mixedrole");

        assertThat(run("MixedRole", "ADMIN")).isZero();

        assertThat(accounts.isAdmin(actorId)).isTrue();
    }

    @Test
    void settingTheSameRoleChangesNothing() {
        var actorId = account("unchanged");
        var authorization = authorization(actorId.toString(), "read admin:read");
        consent(actorId, "SCOPE_admin:read");

        assertThat(run("unchanged", "user")).isZero();

        assertThat(out.toString()).contains("already a regular user");
        assertThat(authorizationExists(authorization)).isTrue();
        assertThat(consentExists(actorId)).isTrue();
    }

    @Test
    void changingTheRoleRevokesOnlyThatAccountsAdminGrants() {
        var actorId = account("revoking");
        var otherActorId = account("unrelated");
        var admin = authorization(actorId.toString(), "read,admin:read");
        var granular = authorization(actorId.toString(), "admin:write:reports");
        var plain = authorization(actorId.toString(), "read,write");
        var otherAccount = authorization(otherActorId.toString(), "admin:read");
        consent(actorId, "SCOPE_read,SCOPE_admin:read");
        consent(otherActorId, "SCOPE_admin:read");

        assertThat(run("revoking", "admin")).isZero();

        assertThat(out.toString()).contains("lost their access");
        assertThat(authorizationExists(admin)).isFalse();
        assertThat(authorizationExists(granular)).isFalse();
        assertThat(authorizationExists(plain)).isTrue();
        assertThat(authorizationExists(otherAccount)).isTrue();
        assertThat(consentExists(actorId)).isFalse();
        assertThat(consentExists(otherActorId)).isTrue();
    }

    @Test
    void removingTheRoleAlsoRevokesAdminGrants() {
        var actorId = account("demoted");
        assertThat(run("demoted", "admin")).isZero();
        var authorization = authorization(actorId.toString(), "admin:read");

        assertThat(run("demoted", "user")).isZero();

        assertThat(authorizationExists(authorization)).isFalse();
    }

    @Test
    void doesNotMentionAppsWhenNoneLostAccess() {
        account("noapps");

        assertThat(run("noapps", "admin")).isZero();

        assertThat(out.toString()).doesNotContain("lost their access");
    }

    @Test
    void warnsWhenNoAdminsRemain() {
        jdbc.sql("UPDATE account_credential SET role = 'USER'").update();
        account("lastadmin");
        assertThat(run("lastadmin", "admin")).isZero();
        assertThat(out.toString()).doesNotContain("no admins");

        assertThat(run("lastadmin", "user")).isZero();

        assertThat(out.toString()).contains("no admins");
    }

    @Test
    void warnsAboutNoAdminsEvenIfNothingChanged() {
        jdbc.sql("UPDATE account_credential SET role = 'USER'").update();
        account("stilluser");

        assertThat(run("stilluser", "user")).isZero();

        assertThat(out.toString()).contains("already a regular user").contains("no admins");
    }

    @Test
    void warnsThatSessionsSurviveRemovingTheRole() {
        account("sessions");

        assertThat(run("sessions", "admin")).isZero();
        assertThat(out.toString()).doesNotContain("reset-password");

        assertThat(run("sessions", "user")).isZero();
        assertThat(out.toString()).contains("run reset-password before giving it back");
    }

    @Test
    void refusesAnUnknownUsername() {
        assertThat(run("nobody", "admin")).isOne();

        assertThat(err.toString()).contains("no local account");
    }

    @Test
    void refusesANonLocalActor() {
        var uri = instance.resolve("/users/remote").toString();
        jdbc.sql("""
                        INSERT INTO actor (id, uri, username, domain, inbox_uri, outbox_uri, public_key_pem)
                        VALUES (:id, :uri, 'remote', :domain, :uri, :uri, 'key')
                        """)
                .param("id", UUID.randomUUID())
                .param("uri", uri)
                .param("domain", instance.domain())
                .update();

        assertThat(run("remote", "admin")).isOne();

        assertThat(err.toString()).contains("no local account");
    }

    @Test
    void refusesAnAccountWithoutAPassword() {
        actors.createLocalIfAbsent("nopassword", actor -> {
        });

        assertThat(run("nopassword", "admin")).isOne();

        assertThat(err.toString()).contains("reset-password");
    }

    @Test
    void refusesAnUnknownRole() {
        var actorId = account("wrongrole");

        assertThat(run("wrongrole", "owner")).isEqualTo(CommandLine.ExitCode.USAGE);

        assertThat(err.toString()).contains("admin or user");
        assertThat(accounts.isAdmin(actorId)).isFalse();
    }

    private UUID account(String username) {
        assertThat(accounts.signUp(username, PASSWORD)).isTrue();
        return actors.findLocalByUsername(username).orElseThrow().id();
    }

    private String authorization(String principalName, String scopes) {
        var id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO oauth2_authorization
                            (id, registered_client_id, principal_name, authorization_grant_type, authorized_scopes)
                        VALUES (:id, 'some-client', :principalName, 'authorization_code', :scopes)
                        """)
                .param("id", id)
                .param("principalName", principalName)
                .param("scopes", scopes)
                .update();
        return id;
    }

    private boolean authorizationExists(String id) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single() == 1;
    }

    private void consent(UUID actorId, String authorities) {
        jdbc.sql("""
                        INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, authorities)
                        VALUES ('some-client', :principalName, :authorities)
                        """)
                .param("principalName", actorId.toString())
                .param("authorities", authorities)
                .update();
    }

    private boolean consentExists(UUID actorId) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization_consent WHERE principal_name = :principalName")
                .param("principalName", actorId.toString())
                .query(Long.class)
                .single() == 1;
    }

    private int run(String... args) {
        var command = new SetRoleCommand();
        command.wire(actors, accounts);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
