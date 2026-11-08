package dev.biribiri.elysium.auth.internal;

import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "list-sign-ups",
        mixinStandardHelpOptions = true,
        description = "Lists the sign-ups that have confirmed their email address and are waiting for an admin to approve "
                + "them, oldest first.")
class ListSignUpsCommand implements Callable<Integer> {

    private SignUps signUps;

    @Spec
    private CommandSpec spec;

    @Autowired
    void wire(SignUps signUps) {
        this.signUps = signUps;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var waiting = signUps.awaitingApproval();
        if (waiting.isEmpty()) {
            out.println("No sign-ups are waiting for approval");
            return 0;
        }
        for (var pending : waiting) {
            out.println(pending.username() + "  " + pending.email() + "  confirmed " + pending.confirmedAt());
            if (pending.reason() != null) {
                // Anyone can write a reason, and escape sequences in it would be run by the admin's terminal.
                pending.reason().lines().forEach(line -> out.println("    " + line.replaceAll("\\p{Cntrl}", "?")));
            }
        }
        return 0;
    }
}
