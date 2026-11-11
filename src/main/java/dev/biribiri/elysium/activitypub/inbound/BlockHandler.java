package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.vocab.Accept;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.BlockService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Stores a remote actor's block of a local account, so that the account can't follow it or be followed by it, and its
 * relationship says so. A suspended local account can be blocked too, so that the block holds if it's unsuspended.
 */
@Component
class BlockHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(BlockHandler.class);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final BlockService blocks;
    private final DomainBlocks domainBlocks;
    private final ActivityDeliveries deliveries;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    BlockHandler(
            ActorService actors,
            RemoteActors remoteActors,
            BlockService blocks,
            DomainBlocks domainBlocks,
            ActivityDeliveries deliveries,
            TransactionTemplate transaction,
            JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.blocks = blocks;
        this.domainBlocks = domainBlocks;
        this.deliveries = deliveries;
        this.transaction = transaction;
        this.json = json;
    }

    @Override
    public String type() {
        return "Block";
    }

    @Override
    public void handle(InboundActivity activity) {
        var target = ActivityObject.of(activity.body(), json)
                .map(ActivityObject::id)
                .flatMap(ActivityObject::parseUri)
                .flatMap(actors::findAnyLocalByUri)
                .filter(found -> !found.isDeleted());
        if (target.isEmpty()) {
            log.debug("Ignoring Block {}, which isn't of a local account", activity.id());
            return;
        }
        if (blocks.wasUndone(activity.id(), activity.actorUri(), target.get().id(), activity.firstReceivedAt())) {
            return;
        }
        // Before the transaction, because this can make a request to another server.
        var blocker = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        transaction.executeWithoutResult(status -> {
            // Fetching the actor can take a while, and its server can have been blocked since the job started.
            if (domainBlocks.isBlocked(blocker.uri().getHost()) || actors.isInactive(blocker.uri())) {
                return;
            }
            blocks.receive(blocker, target.get(), activity.id(), activity.firstReceivedAt())
                    .ifPresent(removed -> cancelAccept(target.get(), removed.activityUri()));
        });
    }

    /**
     * The actor's follow has gone with the block, so an answer to it that's still waiting shouldn't be sent.
     */
    private void cancelAccept(Actor target, String followActivity) {
        if (followActivity != null) {
            deliveries.cancel(Accept.idFor(target.uri(), followActivity));
        }
    }
}
