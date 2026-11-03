package dev.biribiri.elysium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class CommandRunnerTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void aServerStartIsNotACommand(CapturedOutput output) {
        assertThat(run()).isZero();
        assertThat(run("--server.port=9000")).isZero();

        assertThat(output).doesNotContain("Usage:");
    }

    @Test
    void findsCommandsInTheContext() {
        assertThat(new CommandRunner(context).commandLine().getSubcommands())
                .containsKeys("create-user", "reset-password", "help");
    }

    @Test
    void rejectsUnknownCommandsAndWrongArguments(CapturedOutput output) {
        assertThat(run("create-usr", "alice")).isEqualTo(2);
        assertThat(run("create-user")).isEqualTo(2);
        assertThat(run("create-user", "alice", "bob")).isEqualTo(2);
        assertThat(run("reset-password")).isEqualTo(2);

        assertThat(output).contains("Usage:");
    }

    @Test
    void printsHelp(CapturedOutput output) {
        assertThat(run("help")).isZero();
        assertThat(output).contains("create-user").contains("reset-password");

        assertThat(run("create-user", "--help")).isZero();
        assertThat(output).contains("USERNAME");
    }

    private int run(String... args) {
        var runner = new CommandRunner(context);
        runner.run(new DefaultApplicationArguments(args));
        return runner.getExitCode();
    }
}
