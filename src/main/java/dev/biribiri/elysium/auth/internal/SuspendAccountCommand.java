package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "suspend-account",
        mixinStandardHelpOptions = true,
        description = "Suspends a local or remote account, keeping its data. Takes effect without restarting the server.")
class SuspendAccountCommand implements Callable<Integer> {

    private ActorService actors;
    private AccountActions accounts;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "ACCOUNT", description = "A local username, or the URI of a remote account.")
    private String account;

    @Autowired
    void wire(ActorService actors, AccountActions accounts) {
        this.actors = actors;
        this.accounts = accounts;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var actor = AccountCommandTarget.find(actors, account);
        if (actor.isEmpty()) {
            err.println("There's no account named " + account);
            return 1;
        }
        try {
            accounts.suspend(actor.get().id(), Optional.empty());
        } catch (AccountActionRefusedException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println(account + " is suspended. If the server is running, it finishes this within about 10 minutes, including "
                + "telling other servers.");
        return 0;
    }
}
