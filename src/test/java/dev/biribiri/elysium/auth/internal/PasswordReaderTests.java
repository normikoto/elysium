package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.api.Test;

import static dev.biribiri.elysium.auth.internal.PasswordReader.FromTerminalOrStdin.isTerminalDevice;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordReaderTests {

    @Test
    void recognizesTerminals() {
        assertThat(isTerminalDevice("/dev/pts/3")).isTrue();
        assertThat(isTerminalDevice("/dev/tty1")).isTrue();
        assertThat(isTerminalDevice("/dev/console")).isTrue();
    }

    @Test
    void doesNotMistakePipesAndFilesForTerminals() {
        assertThat(isTerminalDevice("pipe:[12345]")).isFalse();
        assertThat(isTerminalDevice("socket:[12345]")).isFalse();
        assertThat(isTerminalDevice("/dev/null")).isFalse();
        assertThat(isTerminalDevice("/home/someone/password.txt")).isFalse();
        assertThat(isTerminalDevice("")).isFalse();
    }
}
