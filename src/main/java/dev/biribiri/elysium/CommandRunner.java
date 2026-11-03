package dev.biribiri.elysium;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

/**
 * Runs the command named on the command line, if there is one. To add a command, annotate a bean with {@link Command}
 * and have it implement {@link java.util.concurrent.Callable Callable&lt;Integer&gt;}, returning the exit code.
 */
@Component
class CommandRunner implements ApplicationRunner, ExitCodeGenerator {

    private final ApplicationContext context;
    private int exitCode;

    CommandRunner(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Anything else starts the server, including options such as {@code --server.port=9000}.
     */
    static boolean isCommand(String[] args) {
        return args.length > 0 && !args[0].startsWith("-");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isCommand(args.getSourceArgs())) {
            exitCode = commandLine().execute(args.getSourceArgs());
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    CommandLine commandLine() {
        var commandLine = new CommandLine(new Root());
        context.getBeansWithAnnotation(Command.class).values().forEach(commandLine::addSubcommand);
        return commandLine;
    }

    @Command(name = "elysium", description = "Manages an Elysium server.", subcommands = HelpCommand.class)
    private static class Root {
    }
}
