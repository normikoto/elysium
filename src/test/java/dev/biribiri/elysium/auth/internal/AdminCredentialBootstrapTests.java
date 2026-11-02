package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.TestcontainersConfiguration;
import dev.biribiri.elysium.actor.ActorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AdminCredentialBootstrapTests {

    private static final String WARNING = "wasn't applied because the account";

    @Autowired
    private ActorService actors;

    @Autowired
    private LocalAccounts accounts;

    @Test
    void warnsWhenTheAccountAlreadyHasADifferentPassword(CapturedOutput output) {
        assertThat(accounts.signUp("claimed", "stranger's password")).isTrue();

        run("claimed", "the configured password");

        assertThat(output).contains(WARNING).doesNotContain("the configured password");
        var actorId = actors.findLocalByUsername("claimed").orElseThrow().id();
        assertThat(accounts.passwordMatches(actorId, "stranger's password")).isTrue();
    }

    @Test
    void doesNotWarnWhenThePasswordIsAlreadySet(CapturedOutput output) {
        actors.createLocalIfAbsent("unchanged");
        run("unchanged", "the configured password");
        run("unchanged", "the configured password");

        assertThat(output).doesNotContain(WARNING);
    }

    @Test
    void readsEnvironmentVariablesLiterally() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test", Map.of("ELYSIUM_BOOTSTRAP_ADMIN_PASSWORD", "a${b}#{c}")));
        ConfigurationPropertySources.attach(environment);

        assertThat(AdminCredentialBootstrap.literalPassword(environment)).isEqualTo("a${b}#{c}");
    }

    @Test
    void anUnsetPasswordIsEmpty() {
        var environment = new StandardEnvironment();
        ConfigurationPropertySources.attach(environment);

        assertThat(AdminCredentialBootstrap.literalPassword(environment)).isEmpty();
    }

    private void run(String username, String password) {
        new AdminCredentialBootstrap(actors, accounts, username, password).run(new DefaultApplicationArguments());
    }
}
