package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.Ids;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "revoke-invite",
        mixinStandardHelpOptions = true,
        description = "Stops an invite from working. Accounts it has already made stay.")
class RevokeInviteCommand implements Callable<Integer> {

    private Invites invites;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "ID", description = "The invite's ID, from list-invites.")
    private String id;

    @Autowired
    void wire(Invites invites) {
        this.invites = invites;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var parsed = Ids.parse(id);
        if (parsed.isEmpty() || !invites.revoke(parsed.get())) {
            err.println("There's no working invite with the ID " + id);
            return 1;
        }
        out.println("Revoked invite " + id);
        return 0;
    }
}
