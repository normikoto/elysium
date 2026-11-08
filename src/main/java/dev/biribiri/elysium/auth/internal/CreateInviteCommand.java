package dev.biribiri.elysium.auth.internal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.time.Instant;
import java.util.concurrent.Callable;

@Command(
        name = "create-invite",
        mixinStandardHelpOptions = true,
        description = "Makes a link that lets someone create an account, even if sign-ups are closed. They don't need to "
                + "confirm an email address or wait for approval. Prints the link, which can't be shown again.")
class CreateInviteCommand implements Callable<Integer> {

    private Invites invites;

    @Spec
    private CommandSpec spec;

    @Option(names = "--max-uses", paramLabel = "N", description = "How many accounts it can make. Defaults to no limit.")
    private Integer maxUses;

    @Option(names = "--expires-in", paramLabel = "DURATION",
            description = "How long it works for, such as 12h or 7d. Defaults to forever.")
    private String expiresIn;

    @Autowired
    void wire(Invites invites) {
        this.invites = invites;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        if (maxUses != null && maxUses < 1) {
            err.println("--max-uses has to be at least 1");
            return 1;
        }
        Instant expiresAt = null;
        if (expiresIn != null) {
            try {
                var duration = DurationStyle.detectAndParse(expiresIn);
                if (duration.isNegative() || duration.isZero()) {
                    err.println("--expires-in has to be positive");
                    return 1;
                }
                expiresAt = Instant.now().plus(duration);
            } catch (IllegalArgumentException e) {
                err.println("--expires-in has to be a duration, such as 12h or 7d");
                return 1;
            }
        }

        var created = invites.create(maxUses, expiresAt);
        out.println("Created invite " + created.id());
        out.println(created.url());
        return 0;
    }
}
