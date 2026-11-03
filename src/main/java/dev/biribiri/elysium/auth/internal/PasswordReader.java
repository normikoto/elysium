package dev.biribiri.elysium.auth.internal;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Reads a new password for a command. Passwords aren't taken as arguments because those show up in {@code ps} and in
 * shell history.
 */
@FunctionalInterface
interface PasswordReader {

    /**
     * @return the password, or null if there's no input
     * @throws IllegalArgumentException if the password can't be read safely, or it and its confirmation don't match
     */
    String read();

    /**
     * Prompts on a terminal, and otherwise reads a line from stdin so that scripts can pipe in a password.
     */
    @Component
    class FromTerminalOrStdin implements PasswordReader {

        /**
         * {@link java.io.Console#isTerminal()} needs both stdin and stdout to be terminals, so it doesn't tell a piped
         * password apart from someone typing with their output redirected. Their password would be shown as they type
         * it, and a typo wouldn't be caught.
         */
        static boolean isTerminalDevice(String path) {
            return path.startsWith("/dev/pts/") || path.startsWith("/dev/tty") || path.equals("/dev/console");
        }

        private static String stdinDevice() {
            try {
                return Files.readSymbolicLink(Path.of("/proc/self/fd/0")).toString();
            } catch (IOException | UnsupportedOperationException e) {
                // Not on Linux, so there's no way to check.
                return "";
            }
        }

        @Override
        public String read() {
            var console = System.console();
            // Depending on the JDK, there can be a console even when stdin is redirected.
            if (console == null || !console.isTerminal()) {
                if (isTerminalDevice(stdinDevice())) {
                    throw new IllegalArgumentException("Can't hide the password while output is redirected. "
                            + "Run this without redirecting its output, or pipe the password in.");
                }
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
