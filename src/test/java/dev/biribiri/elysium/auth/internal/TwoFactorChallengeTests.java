package dev.biribiri.elysium.auth.internal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TwoFactorChallengeTests {

    private final UUID actorId = UUID.randomUUID();

    @Test
    void isFoundUntilItExpires() {
        var request = new MockHttpServletRequest();
        TwoFactorChallenge.start(request, actorId, 3);

        assertThat(TwoFactorChallenge.current(request)).hasValueSatisfying(challenge -> {
            assertThat(challenge.actorId()).isEqualTo(actorId);
            assertThat(challenge.loginGeneration()).isEqualTo(3);
        });

        request.getSession().setAttribute(
                "elysium.twoFactorChallenge", new TwoFactorChallenge(actorId, 3, Instant.now().minusSeconds(1), 5));

        assertThat(TwoFactorChallenge.current(request)).isEmpty();
        assertThat(request.getSession().getAttribute("elysium.twoFactorChallenge")).isNull();
    }

    @Test
    void endsAfterItsLastAttempt() {
        var request = new MockHttpServletRequest();
        TwoFactorChallenge.start(request, actorId, 0);

        for (int i = 0; i < 4; i++) {
            assertThat(TwoFactorChallenge.current(request).orElseThrow().failed(request)).isTrue();
        }

        assertThat(TwoFactorChallenge.current(request).orElseThrow().failed(request)).isFalse();
        assertThat(TwoFactorChallenge.current(request)).isEmpty();
    }

    @Test
    void isNotFoundWithoutASession() {
        assertThat(TwoFactorChallenge.current(new MockHttpServletRequest())).isEmpty();
    }
}
