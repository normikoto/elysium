package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.auth.AccountActionRefusedException;
import dev.biribiri.elysium.auth.AccountActions;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Callable;

@Command(
        name = "delete-account",
        mixinStandardHelpOptions = true,
        description = "Permanently deletes a local account and its posts. This can't be undone, and the username can't be "
                + "used again.")
class DeleteAccountCommand implements Callable<Integer> {

    private ActorService actors;
    private AccountActions accounts;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "USERNAME", description = "The account's username.")
    private String username;

    @Option(names = "--yes", description = "Don't ask for confirmation.")
    private boolean confirmed;

    @Autowired
    void wire(ActorService actors, AccountActions accounts) {
        this.actors = actors;
        this.accounts = accounts;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var actor = actors.findAnyLocalByUsername(username).filter(found -> !found.isDeleted());
        if (actor.isEmpty()) {
            err.println("There's no local account named " + username);
            return 1;
        }
        if (!confirmed && !confirmedByTyping(out)) {
            err.println("Nothing was deleted.");
            return 1;
        }
        try {
            accounts.delete(actor.get().id(), Optional.empty());
        } catch (AccountActionRefusedException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.println(username + " is deleted. If the server is running, it removes the account's posts and tells other "
                + "servers within about 10 minutes.");
        return 0;
    }

    private boolean confirmedByTyping(PrintWriter out) {
        out.print("This can't be undone. Type the username to confirm: ");
        out.flush();
        var input = new Scanner(System.in);
        return input.hasNextLine() && input.nextLine().strip().equals(username);
    }
}
