package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.RegistrationProperties;
import dev.biribiri.elysium.actor.RegistrationProperties.Mode;
import dev.biribiri.elysium.mail.Mailer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationChecksTests {

    @Test
    void signUpsThatNeedEmailNeedAnSmtpServer() {
        for (var mode : new Mode[]{Mode.OPEN, Mode.APPROVAL}) {
            assertThatThrownBy(() -> new RegistrationChecks(new RegistrationProperties(mode, null), mailer(false)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.mail.host");
            assertThatCode(() -> new RegistrationChecks(new RegistrationProperties(mode, null), mailer(true)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void invitesDontNeedAnSmtpServer() {
        assertThatCode(() -> new RegistrationChecks(new RegistrationProperties(Mode.CLOSED, null), mailer(false)))
                .doesNotThrowAnyException();
    }

    @Test
    void theOldSettingIsRefused() {
        assertThatThrownBy(() -> new RegistrationProperties(Mode.CLOSED, true))
                .hasMessageContaining("elysium.registration.mode");
        assertThatThrownBy(() -> new RegistrationProperties(Mode.CLOSED, false))
                .hasMessageContaining("elysium.registration.mode");
    }

    private static Mailer mailer(boolean configured) {
        return new Mailer() {
            @Override
            public boolean isConfigured() {
                return configured;
            }

            @Override
            public void send(String to, String subject, String body) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
