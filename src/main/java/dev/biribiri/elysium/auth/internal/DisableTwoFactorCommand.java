package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "disable-two-factor",
        mixinStandardHelpOptions = true,
        description = "Turns off two-factor login for a local account that's lost its authenticator app and its recovery "
                + "codes, so that its password is enough to log in.")
class DisableTwoFactorCommand implements Callable<Integer> {

    private ActorService actors;
    private TwoFactorAccounts twoFactor;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "USERNAME", description = "The account's username.")
    private String username;

    @Autowired
    void wire(ActorService actors, TwoFactorAccounts twoFactor) {
        this.actors = actors;
        this.twoFactor = twoFactor;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        // Including a suspended account, as reset-password does.
        var actor = actors.findAnyLocalByUsername(username).filter(found -> !found.isDeleted());
        if (actor.isEmpty()) {
            err.println("There's no local account named " + username);
            return 1;
        }
        if (!twoFactor.disable(actor.get().id())) {
            out.println(username + " doesn't have two-factor login.");
            return 0;
        }
        out.println("Turned off two-factor login for " + username + ", and removed its recovery codes.");
        return 0;
    }
}
