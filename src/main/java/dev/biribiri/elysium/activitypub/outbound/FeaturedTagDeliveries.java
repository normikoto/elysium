package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.delivery.ActivityDeliveries;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity;
import dev.biribiri.elysium.activitypub.delivery.OutboundActivity.WhileSuspended;
import dev.biribiri.elysium.activitypub.vocab.CollectionChange;
import dev.biribiri.elysium.activitypub.vocab.Hashtag;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.content.TagFeatured;
import dev.biribiri.elysium.content.TagUnfeatured;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Sends a local account's featured tags to its followers as {@code Add}s of a {@code Hashtag}, as Mastodon does, and
 * unfeatured ones as {@code Remove}s. Either can run again, since featuring is a set.
 */
@Service
class FeaturedTagDeliveries {

    private final FeaturedTags featuredTags;
    private final ActorService actors;
    private final Recipients recipients;
    private final ActivityDeliveries deliveries;
    private final InstanceProperties instance;
    private final JsonMapper json;

    FeaturedTagDeliveries(FeaturedTags featuredTags, ActorService actors, Recipients recipients,
                          ActivityDeliveries deliveries, InstanceProperties instance, JsonMapper json) {
        this.featuredTags = featuredTags;
        this.actors = actors;
        this.recipients = recipients;
        this.deliveries = deliveries;
        this.instance = instance;
        this.json = json;
    }

    /**
     * Nothing is sent for a tag unfeatured since, whose {@code Remove} is then sent alone, and other servers that never
     * had it ignore that.
     */
    @ApplicationModuleListener
    public void on(TagFeatured event) {
        var actor = sender(event.accountId());
        if (actor == null) return;
        var tag = featuredTags.find(actor.id(), event.featuredTagId()).orElse(null);
        if (tag == null) return;
        var inboxes = inboxes(actor.id());
        if (inboxes.isEmpty()) return;
        var add = CollectionChange.featureTag(addId(actor, event.featuredTagId()), actor, Hashtag.of(tag.name(), instance));
        deliveries.enqueue(new OutboundActivity(add.id(), actor.id(), json.writeValueAsString(add), WhileSuspended.WAIT),
                inboxes);
    }

    /**
     * Sends the {@code Remove} after any {@code Add} still being sent, since a server that got them the other way round
     * would keep the tag featured.
     */
    @ApplicationModuleListener
    public void on(TagUnfeatured event) {
        var actor = sender(event.accountId());
        if (actor == null) return;
        var addId = addId(actor, event.featuredTagId());
        deliveries.cancel(addId);
        var inboxes = inboxes(actor.id());
        if (inboxes.isEmpty()) return;
        var remove = CollectionChange.unfeatureTag(CollectionChange.removalIdFor(addId), actor,
                Hashtag.of(event.name(), instance));
        deliveries.enqueueAfter(new OutboundActivity(remove.id(), actor.id(), json.writeValueAsString(remove),
                WhileSuspended.WAIT), inboxes, addId);
    }

    private Actor sender(UUID accountId) {
        return actors.findAnyLocalById(accountId).filter(found -> !found.isDeleted()).orElse(null);
    }

    /**
     * Each featuring of a tag has an ID of its own, so featuring it again after unfeaturing it isn't taken for a repeat.
     */
    private static String addId(Actor actor, UUID featuredTagId) {
        return actor.uri() + "#featured_tags/" + featuredTagId;
    }

    /**
     * Featured tags are on the profile, which anyone can see, so they go to every follower.
     */
    private List<URI> inboxes(UUID actorId) {
        var inboxes = new LinkedHashSet<URI>();
        recipients.addFollowers(actorId, Visibility.PUBLIC, Instant.now(), inboxes);
        return List.copyOf(inboxes);
    }
}
