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
        description = "Replaces a local account's password, logs it out of every browser, and revokes its applications' access. "
                + "Asks for the password.")
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
        // Including a suspended account, which may be suspended because it's compromised.
        var actor = actors.findAnyLocalByUsername(username).filter(found -> !found.isDeleted());
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
        out.println("Changed the password for " + username + ", logged it out of every browser, and revoked its "
                + "applications' access.");
        return 0;
    }
}
