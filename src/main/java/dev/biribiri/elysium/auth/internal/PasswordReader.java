package dev.biribiri.elysium.auth.internal;

import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Reads a new password for a command. Passwords aren't taken as arguments because those show up in {@code ps} and in
 * shell history.
 */
@FunctionalInterface
interface PasswordReader {

    /**
     * @return the password, or null if there's no input
     * @throws IllegalArgumentException if the password and its confirmation don't match
     */
    String read();

    /**
     * Prompts on a terminal, and otherwise reads a line from stdin so that scripts can pipe in a password.
     */
    @Component
    class FromTerminalOrStdin implements PasswordReader {

        @Override
        public String read() {
            var console = System.console();
            // Depending on the JDK, there can be a console even when stdin is redirected.
            if (console == null || !console.isTerminal()) {
                return IO.readln();
            }

            var password = console.readPassword("Password: ");
            var confirmation = console.readPassword("Confirm password: ");
            if (password == null) {
                return null;
            }
            if (!Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("The passwords don't match");
            }
            return new String(password);
        }
    }
}
