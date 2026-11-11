package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.http.FederationProperties;
import dev.biribiri.elysium.activitypub.vocab.Block;
import dev.biribiri.elysium.activitypub.vocab.Undo;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.AccountBlocked;
import dev.biribiri.elysium.graph.AccountUnblocked;
import dev.biribiri.elysium.graph.BlockService;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Sends a local account's blocks of remote actors, and their {@code Undo}s, unless {@code elysium.federation.send-blocks}
 * is off. Either way the follows between them are ended with their own activities, so the other server's counts stay
 * right. Both go to the blocked actor's own inbox, never a shared one, because no one else is meant to know. Both are safe
 * to run again, and do nothing for what's since been removed.
 */
@Service
public class BlockDeliveries {

    private final ActorService actors;
    private final BlockService blocks;
    private final ActivityDeliveries deliveries;
    private final FederationProperties properties;
    private final JsonMapper json;

    BlockDeliveries(ActorService actors, BlockService blocks, ActivityDeliveries deliveries, FederationProperties properties,
                    JsonMapper json) {
        this.actors = actors;
        this.blocks = blocks;
        this.deliveries = deliveries;
        this.properties = properties;
        this.json = json;
    }

    /**
     * Only a block that's still in place is sent, so that one lifted before this runs isn't sent after its {@code Undo}.
     */
    @ApplicationModuleListener
    public void on(AccountBlocked event) {
        if (!properties.sendBlocks() || !blocks.isCurrent(event.blockerId(), event.blockedId(), event.activityUri())) {
            return;
        }
        var blocker = actors.findAnyLocalById(event.blockerId());
        var blocked = actors.findById(event.blockedId());
        if (blocker.isEmpty() || blocker.get().isDeleted() || blocked.isEmpty() || !blocked.get().isActive()
                || actors.isLocal(blocked.get())) {
            return;
        }
        var block = Block.of(event.activityUri(), blocker.get().uri(), blocked.get().uri());
        deliveries.enqueue(
                new OutboundActivity(block.id(), blocker.get().id(), json.writeValueAsString(block), WhileSuspended.WAIT),
                List.of(blocked.get().inboxUri()));
    }

    /**
     * Cancels the {@code Block} if it hasn't been sent yet, and sends the {@code Undo} after it in case it's being sent right
     * now. A block made while sending them was off gets an {@code Undo} too, which other servers ignore.
     */
    @ApplicationModuleListener
    public void on(AccountUnblocked event) {
        if (!properties.sendBlocks()) {
            return;
        }
        var blocker = actors.findAnyLocalById(event.blockerId());
        var blocked = actors.findById(event.blockedId());
        if (blocker.isEmpty() || blocker.get().isDeleted() || blocked.isEmpty() || !blocked.get().isActive()
                || actors.isLocal(blocked.get())) {
            return;
        }
        var undo = Undo.of(Block.of(event.activityUri(), blocker.get().uri(), blocked.get().uri()));
        deliveries.cancel(event.activityUri());
        deliveries.enqueueAfter(
                new OutboundActivity(undo.id(), blocker.get().id(), json.writeValueAsString(undo), WhileSuspended.WAIT),
                List.of(blocked.get().inboxUri()),
                event.activityUri());
    }
}
