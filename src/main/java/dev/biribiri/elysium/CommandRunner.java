package dev.biribiri.elysium;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Runs the command named on the command line. To add a command, write a class anywhere in this application, annotated
 * with {@link Command} and implementing {@link Callable Callable&lt;Integer&gt;} to return the exit code. It needs a
 * no-argument constructor, because the command line is parsed before Spring starts, so that help and mistakes don't need
 * a working configuration or migrate the database. Spring then calls its {@link Autowired} methods before running it.
 */
final class CommandRunner {

    private static final Set<String> HELP_OPTIONS = Set.of("-h", "--help");

    /**
     * These take priority over all other configuration, including options on the command line. A command can run
     * alongside the server, so it can't open the same ports. Without the quieter logging, a password prompt gets lost in
     * startup messages. A command would also exit in the middle of any job it picked up, so it doesn't run them, and it leaves
     * removing expired login sessions to the server.
     */
    private static final Map<String, Object> COMMAND_PROPERTIES = Map.of(
            "server.port", "-1",
            "management.server.port", "-1",
            "spring.main.banner-mode", "off",
            "logging.level.root", "ERROR",
            "elysium.jobs.worker-enabled", "false",
            "spring.session.jdbc.cleanup-cron", "-");

    private CommandRunner() {
    }

    /**
     * The server takes no arguments other than options such as {@code --server.port=9000}, so anything else is a command.
     */
    static boolean isCommand(String[] args) {
        return Arrays.stream(args).anyMatch(CommandRunner::startsCommand);
    }

    static int run(String[] args) throws Exception {
        return run(args, springArgs -> application().run(springArgs));
    }

    static int run(String[] args, Function<String[], ConfigurableApplicationContext> startSpring) throws Exception {
        // Options before the command are for Spring, such as --spring.profiles.active=prod create-user alice.
        var commandStart = 0;
        while (!startsCommand(args[commandStart])) {
            commandStart++;
        }
        var springArgs = Arrays.copyOfRange(args, 0, commandStart);
        var commandArgs = Arrays.copyOfRange(args, commandStart, args.length);

        var commandLine = commandLine();
        ParseResult parsed;
        try {
            parsed = commandLine.parseArgs(commandArgs);
        } catch (ParameterException e) {
            return commandLine.getParameterExceptionHandler().handleParseException(e, commandArgs);
        }
        if (CommandLine.printHelpIfRequested(parsed)) {
            return CommandLine.ExitCode.OK;
        }

        var command = parsed.asCommandLineList().getLast().getCommand();
        try (var context = startSpring.apply(springArgs)) {
            context.getAutowireCapableBeanFactory().autowireBean(command);
            return ((Callable<Integer>) command).call();
        }
    }

    static SpringApplication application() {
        return new SpringApplication(ElysiumApplication.class) {
            @Override
            protected void configurePropertySources(ConfigurableEnvironment environment, String[] args) {
                super.configurePropertySources(environment, args);
                environment.getPropertySources().addFirst(new MapPropertySource("command", COMMAND_PROPERTIES));
            }
        };
    }

    static CommandLine commandLine() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Command.class));
        scanner.addExcludeFilter(new AssignableTypeFilter(Root.class));

        var commandLine = new CommandLine(new Root());
        for (var candidate : scanner.findCandidateComponents(ElysiumApplication.class.getPackageName())) {
            commandLine.addSubcommand(ClassUtils.resolveClassName(candidate.getBeanClassName(), null));
        }
        return commandLine;
    }

    private static boolean startsCommand(String arg) {
        return !arg.startsWith("-") || HELP_OPTIONS.contains(arg);
    }

    @Command(
            name = "elysium",
            description = "Manages an Elysium server. Without a command, starts the server.",
            mixinStandardHelpOptions = true,
            subcommands = HelpCommand.class)
    private static class Root {
    }
}
