package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.BoostService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Stores boosts, through {@link Boosts}. A boost of a local status is always for someone here, its author. One of another
 * server's status is only for someone here if they follow the booster and it went to the booster's followers, so that no
 * server can fill the database, or make this server fetch statuses, by delivering to the shared inbox. Being named in it
 * doesn't count, as on Mastodon: a boost only shows in the home timelines of the booster's followers, so naming someone
 * would let any server make this one fetch from any other, and store what it fetched, for no one to see.
 */
@Component
class AnnounceHandler implements InboundActivityHandler {

    private final BoostService boostService;
    private final Boosts boosts;
    private final LocalRecipients recipients;
    private final InstanceProperties instance;
    private final JsonMapper json;

    AnnounceHandler(BoostService boostService, Boosts boosts, LocalRecipients recipients, InstanceProperties instance,
                    JsonMapper json) {
        this.instance = instance;
        this.boostService = boostService;
        this.boosts = boosts;
        this.recipients = recipients;
        this.json = json;
    }

    @Override
    public String type() {
        return "Announce";
    }

    @Override
    public void handle(InboundActivity activity) {
        var document = json.readTree(activity.body());
        var boosted = Boosts.boosted(document);
        if (boosted.isEmpty()) return;
        var local = boostService.localTarget(boosted.get());
        if (local.isPresent()) {
            boosts.store(activity, document, local.get().id());
            return;
        }
        // Anything of this server's that's worth boosting is stored, so there's nothing to fetch from here.
        if (boosted.get().getHost().equalsIgnoreCase(instance.baseUrl().getHost())) return;
        if (recipients.followersOf(activity, document).isEmpty()) return;
        var stored = boostService.storedTarget(boosted.get());
        if (stored.isPresent()) {
            boosts.store(activity, document, stored.get().id());
        } else if (!boostService.wasWithdrawn(URI.create(activity.id()), URI.create(activity.actorUri()))) {
            boosts.queue(activity);
        }
    }
}
