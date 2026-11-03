package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Component
@Command(
        name = "create-user",
        mixinStandardHelpOptions = true,
        description = "Creates a local account, even if sign-ups are disabled. Asks for its password.")
class CreateUserCommand implements Callable<Integer> {

    private final ActorService actors;
    private final LocalAccounts accounts;
    private final PasswordReader passwords;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "USERNAME", description = "1 to 30 letters, digits, or underscores.")
    private String username;

    CreateUserCommand(ActorService actors, LocalAccounts accounts, PasswordReader passwords) {
        this.actors = actors;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        if (!Actor.isValidLocalUsername(username)) {
            err.println("Usernames must be 1 to 30 letters, digits, or underscores");
            return 1;
        }
        if (actors.findLocalByUsername(username).isPresent()) {
            err.println("The username " + username + " is taken");
            return 1;
        }

        try {
            if (!accounts.signUp(username, passwords.read())) {
                err.println("The username " + username + " is taken");
                return 1;
            }
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println("Created account " + username);
        return 0;
    }
}
