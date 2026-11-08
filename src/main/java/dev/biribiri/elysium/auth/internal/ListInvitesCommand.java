package dev.biribiri.elysium.auth.internal;

import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.time.Instant;
import java.util.concurrent.Callable;

@Command(
        name = "list-invites",
        mixinStandardHelpOptions = true,
        description = "Lists invites, newest first. Their links can't be shown again.")
class ListInvitesCommand implements Callable<Integer> {

    private Invites invites;

    @Spec
    private CommandSpec spec;

    @Autowired
    void wire(Invites invites) {
        this.invites = invites;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var found = invites.list();
        if (found.isEmpty()) {
            out.println("There are no invites");
            return 0;
        }
        var now = Instant.now();
        for (var invite : found) {
            var uses = invite.uses() + (invite.maxUses() == null ? " uses" : " of " + invite.maxUses() + " uses");
            var state = invite.unusableBecause(now)
                    .orElse(invite.expiresAt() == null ? "never expires" : "expires " + invite.expiresAt());
            out.println(invite.id() + "  created " + invite.createdAt() + "  " + uses + "  " + state);
        }
        return 0;
    }
}
