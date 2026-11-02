package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyLimitedPasswordEncoderTests {

    @Test
    void rejectsHashesBeyondTheLimitAndRecoversAfterwards() throws Exception {
        var hashing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var limited = new ConcurrencyLimitedPasswordEncoder(new BlockingEncoder(hashing, finish), 1, Duration.ofMillis(50));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> limited.encode("first"));
            hashing.await();

            assertThatThrownBy(() -> limited.encode("second")).isInstanceOf(PasswordHashingBusyException.class);
            assertThatThrownBy(() -> limited.matches("second", "hash")).isInstanceOf(PasswordHashingBusyException.class);

            finish.countDown();
            assertThat(first.get()).isEqualTo("hash");
        }

        assertThat(limited.encode("third")).isEqualTo("hash");
    }

    @Test
    void releasesItsPermitWhenHashingFails() {
        var failing = new PasswordEncoder() {
            @Override
            public String encode(CharSequence password) {
                throw new IllegalStateException("failed");
            }

            @Override
            public boolean matches(CharSequence password, String encoded) {
                return true;
            }
        };
        var limited = new ConcurrencyLimitedPasswordEncoder(failing, 1, Duration.ofMillis(50));

        assertThatThrownBy(() -> limited.encode("password")).isInstanceOf(IllegalStateException.class);

        assertThat(limited.matches("password", "hash")).isTrue();
    }

    private record BlockingEncoder(CountDownLatch hashing, CountDownLatch finish) implements PasswordEncoder {

        @Override
        public String encode(CharSequence password) {
            hashing.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return "hash";
        }

        @Override
        public boolean matches(CharSequence password, String encoded) {
            return true;
        }
    }
}
