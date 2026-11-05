package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CommandRunnerTests {

    @Test
    void aServerStartIsNotACommand() {
        assertThat(CommandRunner.isCommand(new String[0])).isFalse();
        assertThat(CommandRunner.isCommand(new String[]{"--server.port=9000"})).isFalse();
        assertThat(CommandRunner.isCommand(new String[]{"--server.port=9000", "--debug"})).isFalse();
    }

    @Test
    void anythingElseIsACommand() {
        assertThat(CommandRunner.isCommand(new String[]{"create-user", "alice"})).isTrue();
        assertThat(CommandRunner.isCommand(new String[]{"--spring.profiles.active=prod", "create-user"})).isTrue();
        assertThat(CommandRunner.isCommand(new String[]{"create-usr"})).isTrue();
        assertThat(CommandRunner.isCommand(new String[]{"--help"})).isTrue();
        assertThat(CommandRunner.isCommand(new String[]{"-h"})).isTrue();
    }

    @Test
    void findsCommandsOnTheClasspath() {
        assertThat(CommandRunner.commandLine().getSubcommands())
                .containsOnlyKeys(
                        "create-user",
                        "reset-password",
                        "set-role",
                        "suspend-account",
                        "unsuspend-account",
                        "delete-account",
                        "help");
    }

    @Test
    void rejectsMistakesWithoutStartingSpring(CapturedOutput output) throws Exception {
        assertThat(runWithoutSpring("create-usr", "alice")).isEqualTo(2);
        assertThat(runWithoutSpring("create-user")).isEqualTo(2);
        assertThat(runWithoutSpring("create-user", "alice", "bob")).isEqualTo(2);
        assertThat(runWithoutSpring("reset-password")).isEqualTo(2);
        assertThat(runWithoutSpring("create-user", "alice", "--spring.profiles.active=prod")).isEqualTo(2);

        assertThat(output).contains("Did you mean").contains("Usage:");
    }

    @Test
    void printsHelpWithoutStartingSpring(CapturedOutput output) throws Exception {
        for (var args : List.of(new String[]{"help"}, new String[]{"--help"}, new String[]{"-h"})) {
            assertThat(runWithoutSpring(args)).isZero();
        }
        assertThat(output).contains("create-user").contains("reset-password");

        assertThat(runWithoutSpring("create-user", "--help")).isZero();
        assertThat(runWithoutSpring("help", "create-user")).isZero();
        assertThat(output).contains("USERNAME");
    }

    @Test
    void runsACommandWithSpringsOptionsAndNoPorts(CapturedOutput output) throws Exception {
        var started = new AtomicReference<ConfigurableApplicationContext>();

        var exitCode = CommandRunner.run(
                new String[]{"--spring.application.name=from-option", "--server.port=9000", "reset-password", "nobody"},
                springArgs -> {
                    var application = CommandRunner.application();
                    application.addPrimarySources(List.of(TestcontainersConfiguration.class));
                    started.set(application.run(springArgs));
                    return started.get();
                });

        // Reaching this message means the command's dependencies were all injected.
        assertThat(exitCode).isOne();
        assertThat(output).contains("There's no local account named nobody");
        var environment = started.get().getEnvironment();
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("from-option");
        assertThat(environment.getProperty("server.port")).isEqualTo("-1");
        assertThat(environment.getProperty("management.server.port")).isEqualTo("-1");
        assertThat(environment.getProperty("elysium.jobs.worker-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("spring.session.jdbc.cleanup-cron")).isEqualTo("-");
        assertThat(started.get().isActive()).isFalse();
    }

    private static int runWithoutSpring(String... args) throws Exception {
        return CommandRunner.run(args, springArgs -> {
            throw new AssertionError("Spring shouldn't have started");
        });
    }
}
