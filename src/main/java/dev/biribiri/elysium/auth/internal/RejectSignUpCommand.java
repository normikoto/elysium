package dev.biribiri.elysium.auth.internal;

import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

@Command(
        name = "reject-sign-up",
        mixinStandardHelpOptions = true,
        description = "Removes a sign-up that's waiting for approval, and emails them to say so. Their username and email "
                + "address become free again.")
class RejectSignUpCommand implements Callable<Integer> {

    private SignUps signUps;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "USERNAME", description = "The username they asked for, from list-sign-ups.")
    private String username;

    @Autowired
    void wire(SignUps signUps) {
        this.signUps = signUps;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var pending = signUps.findAwaitingApproval(username);
        if (pending.isEmpty() || !signUps.reject(pending.get().id())) {
            err.println("No sign-up for " + username + " is waiting for approval");
            return 1;
        }
        out.println("Rejected the sign-up for " + pending.get().username());
        return 0;
    }
}
