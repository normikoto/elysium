package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.TypeConversionException;

import java.util.Locale;
import java.util.concurrent.Callable;

@Command(
        name = "set-role",
        mixinStandardHelpOptions = true,
        description = "Gives a local account the admin role or removes it. Takes effect without restarting the server.")
class SetRoleCommand implements Callable<Integer> {

    private ActorService actors;
    private LocalAccounts accounts;

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "USERNAME", description = "The account's username.")
    private String username;

    @Parameters(
            index = "1",
            paramLabel = "ROLE",
            description = "admin, or user to remove the admin role.",
            converter = RoleConverter.class)
    private AccountRole role;

    @Autowired
    void wire(ActorService actors, LocalAccounts accounts) {
        this.actors = actors;
        this.accounts = accounts;
    }

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var actor = actors.findLocalByUsername(username);
        if (actor.isEmpty()) {
            err.println("There's no local account named " + username);
            return 1;
        }

        var change = accounts.setRole(actor.get().id(), role);
        if (change.isEmpty()) {
            err.println(username + " has no password, so it can't log in. Set one first with reset-password.");
            return 1;
        }

        var name = role == AccountRole.ADMIN ? "an admin" : "a regular user";
        if (change.get().changed()) {
            out.println(username + " is now " + name + ".");
            if (change.get().revokedApps()) {
                out.println("Applications that had admin scopes on this account lost their access, and need authorizing again.");
            }
            if (role == AccountRole.USER) {
                // Sessions are in the server's memory, which this process can't reach.
                out.println("Anyone already logged in through a browser stays logged in until the server restarts, and "
                        + "would be an admin again if the role comes back. If the account may be compromised, restart "
                        + "the server before giving it back.");
            }
        } else {
            out.println(username + " is already " + name + ".");
        }
        if (!change.get().adminsRemain()) {
            out.println("This server has no admins. Use this command to choose one.");
        }
        return 0;
    }

    static class RoleConverter implements ITypeConverter<AccountRole> {

        @Override
        public AccountRole convert(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "admin" -> AccountRole.ADMIN;
                case "user" -> AccountRole.USER;
                default -> throw new TypeConversionException("expected admin or user");
            };
        }
    }
}
