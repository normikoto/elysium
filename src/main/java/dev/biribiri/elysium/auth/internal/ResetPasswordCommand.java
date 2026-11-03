package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "reset-password",
        mixinStandardHelpOptions = true,
        description = "Replaces a local account's password and revokes its applications' access. Asks for the password.")
class ResetPasswordCommand implements Callable<Integer> {

    private ActorService actors;
    private LocalAccounts accounts;
    private PasswordReader passwords;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "USERNAME", description = "The account's username.")
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
        var actor = actors.findLocalByUsername(username);
        if (actor.isEmpty()) {
            err.println("There's no local account named " + username);
            return 1;
        }

        try {
            accounts.resetPassword(actor.get().id(), passwords.read());
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println("Changed the password for " + username + " and revoked its applications' access.");
        // Sessions are in the server's memory, which this process can't reach.
        out.println("Anyone already logged in through a browser stays logged in until the server restarts.");
        return 0;
    }
}
