package dev.biribiri.elysium.activitypub.delivery;

import dev.biribiri.elysium.activitypub.http.BlockedTargetException;
import dev.biribiri.elysium.activitypub.jobs.PermanentJobFailure;
import dev.biribiri.elysium.activitypub.signature.SignedSender;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.signing.ActorSigningKeys;
import dev.biribiri.elysium.actor.signing.SigningKey;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The client refusing a request after this job's own check needs exact timing, which mocks can arrange.
 */
class DeliveryTaskBlockTests {

    private static final URI INBOX = URI.create("https://blocked.test/inbox");
    private static final OutboundActivity ACTIVITY =
            new OutboundActivity("https://elysium.test/activities/1", UUID.randomUUID(), "{}");

    private final ActorService actors = mock();
    private final ActorSigningKeys keys = mock();
    private final SignedSender sender = mock();
    private final DomainBlocks blocks = mock();
    private final DeliveryTask task = new DeliveryTask(mock(JdbcClient.class), actors, keys, sender, mock(HostGate.class), blocks);

    @BeforeEach
    void clientRefuses() throws Exception {
        Actor actor = mock();
        when(actors.findLocalById(ACTIVITY.actorId())).thenReturn(Optional.of(actor));
        when(keys.forActor(actor)).thenReturn(Optional.of(mock(SigningKey.class)));
        when(sender.post(any(), any(), any())).thenThrow(mock(BlockedTargetException.class));
    }

    @Test
    void finishesIfTheServerWasBlockedSinceTheJobChecked() {
        when(blocks.isBlocked("blocked.test")).thenReturn(true);

        assertThatCode(() -> task.send(ACTIVITY, INBOX, "blocked.test")).doesNotThrowAnyException();
    }

    @Test
    void stillFailsForOtherRefusals() {
        assertThatThrownBy(() -> task.send(ACTIVITY, INBOX, "blocked.test")).isInstanceOf(PermanentJobFailure.class);
    }
}
