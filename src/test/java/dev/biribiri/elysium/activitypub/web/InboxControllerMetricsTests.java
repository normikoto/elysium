package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.inbound.Inbox;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.signature.SignatureVerifier;
import dev.biribiri.elysium.activitypub.signature.VerifiedSignature;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.Optional;

import static dev.biribiri.elysium.activitypub.vocab.ActivityStreams.ACTIVITY_JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Each request to an inbox has to be counted once, including one that fails with an exception, which mocks can arrange.
 */
class InboxControllerMetricsTests {

    private final Inbox inbox = mock();
    private final SignatureVerifier verifier = mock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InboxController(
                    mock(ActorService.class), inbox, verifier, mock(ClientAddresses.class),
                    mock(AddressFetchBudgets.class), mock(RefusedDeliveries.class), meters))
            .build();

    @Test
    void countsAnAcceptedActivity() {
        when(verifier.verify(any(), any())).thenReturn(mock(VerifiedSignature.class));
        when(inbox.receive(any(), any(), any())).thenReturn(new Inbox.Result(Inbox.Outcome.ACCEPTED, Duration.ZERO));

        assertThat(outcomeAfterPosting()).isEqualTo("accepted");
    }

    @Test
    void countsARefusedSignature() {
        var refused = mock(SignatureVerificationException.class);
        when(refused.failure()).thenReturn(Failure.REJECTED);
        when(refused.keyDocument()).thenReturn(Optional.empty());
        when(verifier.verify(any(), any())).thenThrow(refused);

        assertThat(outcomeAfterPosting()).isEqualTo("signature_rejected");
    }

    @Test
    void countsAFailure() {
        when(verifier.verify(any(), any())).thenReturn(mock(VerifiedSignature.class));
        when(inbox.receive(any(), any(), any())).thenThrow(new IllegalStateException());

        assertThat(outcomeAfterPosting()).isEqualTo("error");
    }

    /**
     * @return the only outcome counted, which also checks that the request was counted once
     */
    private String outcomeAfterPosting() {
        catchThrowable(() -> mockMvc.perform(post("/inbox").contentType(ACTIVITY_JSON).content("{}")));
        var counters = meters.find("elysium.federation.inbox.requests").counters();
        assertThat(counters).hasSize(1);
        var counter = counters.iterator().next();
        assertThat(counter.count()).isEqualTo(1);
        return counter.getId().getTag("outcome");
    }
}
