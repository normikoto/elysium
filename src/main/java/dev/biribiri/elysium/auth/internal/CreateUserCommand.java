package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "create-user",
        mixinStandardHelpOptions = true,
        description = "Creates a local account, even if sign-ups are disabled. The server's first account becomes an "
                + "admin. Asks for its password.")
class CreateUserCommand implements Callable<Integer> {

    private ActorService actors;
    private LocalAccounts accounts;
    private PasswordReader passwords;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "USERNAME", description = "1 to 30 letters, digits, or underscores.")
    private String username;

    @Autowired
    void wire(ActorService actors, LocalAccounts accounts, PasswordReader passwords) {
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
        // Suspended and deleted accounts keep their usernames.
        if (actors.findAnyLocalByUsername(username).isPresent()) {
            err.println("The username " + username + " is taken");
            return 1;
        }

        Optional<AccountRole> role;
        try {
            role = accounts.createAccount(username, passwords.read());
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        if (role.isEmpty()) {
            err.println("The username " + username + " is taken");
            return 1;
        }
        out.println("Created account " + username);
        if (role.get() == AccountRole.ADMIN) {
            out.println("It's the server's first account, so it's an admin.");
        }
        return 0;
    }
}
