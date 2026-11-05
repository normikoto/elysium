package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "unsuspend-account",
        mixinStandardHelpOptions = true,
        description = "Ends the suspension of a local or remote account. Takes effect without restarting the server.")
class UnsuspendAccountCommand implements Callable<Integer> {

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
            accounts.unsuspend(actor.get().id());
        } catch (AccountActionRefusedException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println(account + " is no longer suspended. If the server is running, it tells other servers within about 10 "
                + "minutes.");
        return 0;
    }
}
