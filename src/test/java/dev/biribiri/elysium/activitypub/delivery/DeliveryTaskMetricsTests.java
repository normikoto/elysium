package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.http.FederationHttpClient.Response;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import dev.biribiri.elysium.http.BlockedTargetException;
import dev.biribiri.elysium.moderation.DomainBlocks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each way a delivery can end has to be counted, or the outcomes wouldn't add up to the attempts. Mocks can make the
 * client fail in each of those ways, which a real server can't easily do.
 */
class DeliveryTaskMetricsTests {

    private static final URI INBOX = URI.create("https://remote.test/inbox");
    private static final OutboundActivity ACTIVITY =
            new OutboundActivity("https://elysium.test/activities/1", UUID.randomUUID(), "{}");

    private final SignedSender sender = mock();
    private final ActorSigningKeys keys = mock();
    private final Actor actor = mock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeliveryTask task = new DeliveryTask(
            mock(JdbcClient.class), mock(ActorService.class), keys, sender, mock(HostGate.class), mock(DomainBlocks.class), meters);

    @BeforeEach
    void hasAKey() {
        when(keys.forActor(actor)).thenReturn(Optional.of(mock(SigningKey.class)));
    }

    @Test
    void countsADelivery() throws Exception {
        answers(202);
        assertThat(outcomeAfterSending()).isEqualTo("delivered");
    }

    @Test
    void countsARefusal() throws Exception {
        answers(404);
        assertThat(outcomeAfterSending()).isEqualTo("refused");
    }

    @Test
    void countsAnAnswerToRetry() throws Exception {
        answers(503);
        assertThat(outcomeAfterSending()).isEqualTo("retryable");
    }

    @Test
    void countsAnUnreachableServer() throws Exception {
        when(sender.post(any(), any(), any())).thenThrow(new IOException("Connection refused"));
        assertThat(outcomeAfterSending()).isEqualTo("unreachable");
    }

    @Test
    void countsRunningOutOfConnections() throws Exception {
        when(sender.post(any(), any(), any())).thenThrow(new ConnectionRequestTimeoutException());
        assertThat(outcomeAfterSending()).isEqualTo("no_connection");
    }

    @Test
    void countsAnInboxThatIsNotAllowed() throws Exception {
        when(sender.post(any(), any(), any())).thenThrow(mock(BlockedTargetException.class));
        assertThat(outcomeAfterSending()).isEqualTo("not_allowed");
    }

    @Test
    void countsAnInboxThatCannotBeSigned() throws Exception {
        when(sender.post(any(), any(), any())).thenThrow(new IllegalArgumentException());
        assertThat(outcomeAfterSending()).isEqualTo("invalid_inbox");
    }

    @Test
    void countsAnUnexpectedFailure() throws Exception {
        when(sender.post(any(), any(), any())).thenThrow(new IllegalStateException());
        assertThat(outcomeAfterSending()).isEqualTo("error");
    }

    private void answers(int status) throws IOException {
        when(sender.post(any(), any(), any())).thenReturn(new Response(status, null, null, new byte[0], null));
    }

    /**
     * @return the only outcome recorded, which also checks that the attempt was counted once
     */
    private String outcomeAfterSending() {
        catchThrowable(() -> task.send(ACTIVITY, Optional.of(actor), INBOX, "remote.test"));
        var timers = meters.find("elysium.federation.deliveries").timers();
        assertThat(timers).hasSize(1);
        var timer = timers.iterator().next();
        assertThat(timer.count()).isEqualTo(1);
        return timer.getId().getTag("outcome");
    }
}
