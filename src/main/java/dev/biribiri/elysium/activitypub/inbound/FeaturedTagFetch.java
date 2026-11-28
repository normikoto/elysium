package dev.biribiri.elysium.activitypub.inbound;

import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.CompletionHandler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import dev.biribiri.elysium.activitypub.http.HostBusyException;
import dev.biribiri.elysium.activitypub.signature.ActivityDocuments;
import dev.biribiri.elysium.activitypub.signature.DocumentFetchException;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.graph.FollowAccepted;
import dev.biribiri.elysium.jobs.Jobs;
import dev.biribiri.elysium.jobs.PermanentJobFailure;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.text.Hashtags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads the hashtags a remote actor features from its {@code featuredTags} collection, as Mastodon does, when a local
 * account's follow of it is accepted, when it sends an {@code Update} of itself, and when it sends an {@code Add} or
 * {@code Remove} of a {@code Hashtag}. What the collection lists replaces what was featured, so the collection decides,
 * whatever order those activities run in.
 *
 * <p>The collection is read as {@link FeaturedFetch} reads pins: from the actor's own server, as
 * {@link RemoteActorDocument} requires of it, with up to {@link FeaturedTags#MAX_PER_ACCOUNT} items of its first page, and
 * a turn in {@link TargetFetchTurns} for each fetch. Featured tags are public, so the fetch is signed as this server
 * rather than as a follower, and an actor nobody here follows can have them.
 */
@Component
class FeaturedTagFetch {

    static final String TASK_NAME = "featured-tag-fetch";

    static final TaskDescriptor<FeaturedTagFetchJob> TASK = TaskDescriptor.of(TASK_NAME, FeaturedTagFetchJob.class);

    private static final Logger log = LoggerFactory.getLogger(FeaturedTagFetch.class);

    private static final Duration RECHECK_AFTER = Duration.ofMinutes(1);

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final FeaturedTags featuredTags;
    private final ActivityDocuments documents;
    private final TargetFetchTurns turns;
    private final DomainBlocks blocks;
    private final SchedulerClient scheduler;

    /**
     * @param scheduler lazy, because the scheduler needs this job's task, which needs this
     */
    FeaturedTagFetch(
            ActorService actors,
            RemoteActors remoteActors,
            FeaturedTags featuredTags,
            ActivityDocuments documents,
            TargetFetchTurns turns,
            DomainBlocks blocks,
            @Lazy SchedulerClient scheduler) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.featuredTags = featuredTags;
        this.documents = documents;
        this.turns = turns;
        this.blocks = blocks;
        this.scheduler = scheduler;
    }

    @ApplicationModuleListener
    public void on(FollowAccepted event) {
        queue(event.followedId());
    }

    /**
     * One job for each actor, so that several reasons to read the collection at once read it once.
     */
    void queue(UUID actorId) {
        Jobs.schedule(scheduler, TASK, FeaturedTagFetchJob.of(actorId.toString(), now()), TASK_NAME, actorId.toString());
    }

    /**
     * Reads the collection again a minute after an {@code Add} or {@code Remove} of a {@code Hashtag}. A fetch that was
     * already running when it arrived can have read the collection from before the change, and {@link #queue} doesn't
     * queue another while one runs. Each activity has a job of its own, so a later change isn't merged with one that's
     * running either.
     */
    void queueAgain(UUID actorId, String activityId) {
        var now = now();
        Jobs.scheduleAt(scheduler, TASK, FeaturedTagFetchJob.of(actorId.toString(), now), now.plus(RECHECK_AFTER),
                TASK_NAME, actorId.toString(), activityId);
    }

    @Bean
    static Task<FeaturedTagFetchJob> featuredTagsCollectionFetch(ObjectProvider<FeaturedTagFetch> featuredTagFetch) {
        return Jobs.task(TASK, job -> featuredTagFetch.getObject().run(job));
    }

    /**
     * @return what to do with the job, which is to wait if its turn hasn't come yet, or to go on to the collection's first
     * page
     * @throws IllegalStateException if the actor's server couldn't be reached, which is worth trying again
     */
    CompletionHandler<FeaturedTagFetchJob> run(FeaturedTagFetchJob job) {
        var actorId = UUID.fromString(job.actorId());
        var uri = actors.findById(actorId).filter(found -> found.isActive() && !actors.isLocal(found)).map(Actor::uri);
        if (uri.isEmpty() || blocks.isBlocked(uri.get().getHost())) {
            return Jobs.done();
        }
        Actor actor;
        try {
            // Throws if the actor has been suspended or deleted, and refreshes its featured tags collection.
            actor = remoteActors.fetchOrRefresh(uri.get());
        } catch (PermanentJobFailure e) {
            log.debug("Not reading the featured tags of {}: {}", uri.get(), e.getMessage());
            return Jobs.done();
        }
        var target = Optional.ofNullable(job.page()).map(URI::create)
                .or(() -> actors.findFeaturedTagsUri(actor.id()));
        if (target.isEmpty()) {
            // It no longer features any, or never did.
            featuredTags.replaceRemote(actor.id(), List.of());
            return Jobs.done();
        }
        if (!Inbox.isOnActorsServer(target.get(), actor.uri())) {
            return Jobs.done();
        }
        var now = now();
        var turn = turns.take(target.get(), TargetFetchTurns.senderOf(actor.uri()), job.turn(), now);
        if (turn.isEmpty()) {
            log.debug("Not reading the featured tags of {}, since too many fetches are waiting for its server",
                    actor.uri());
            return Jobs.done();
        }
        if (turn.get().isAfter(now)) {
            return Jobs.waitUntil(turn.get(), job.withTurn(turn.get()));
        }
        Optional<JsonNode> document;
        try {
            document = fetch(target.get());
        } catch (HostBusyException e) {
            return Jobs.waitForHost(job, job::withTurn);
        }
        if (document.isEmpty()) {
            return Jobs.done();
        }
        if (job.page() != null) {
            if (ActorCollections.hasId(document.get(), target.get(), "OrderedCollectionPage", "CollectionPage")) {
                replace(document.get(), actor);
            }
            return Jobs.done();
        }
        if (!ActorCollections.hasId(document.get(), target.get(), "OrderedCollection", "Collection")) {
            return Jobs.done();
        }
        var first = document.get().path("first");
        if (first.isObject()) {
            replace(first, actor);
            return Jobs.done();
        }
        if (first.isMissingNode() || first.isNull()) {
            // Mastodon lists the items in the collection itself.
            replace(document.get(), actor);
            return Jobs.done();
        }
        var page = ActorCollections.idOnActorsServer(first, actor);
        if (page.isEmpty()) {
            log.debug("Not reading the featured tags of {}, whose collection's first page isn't on its server",
                    actor.uri());
            return Jobs.done();
        }
        // The page needs a turn of its own.
        return Jobs.rescheduleAt(now, job.forPage(page.get()));
    }

    /**
     * Mastodon serves each as a {@code Hashtag} whose {@code name} has a {@code #}, and reads only that, since its
     * {@code href} is the actor's profile narrowed to the tag.
     */
    private void replace(JsonNode page, Actor actor) {
        var names = ActorCollections.items(page, FeaturedTags.MAX_PER_ACCOUNT).stream()
                .filter(item -> ActivityJson.types(item.path("type")).contains("Hashtag"))
                .flatMap(item -> Hashtags.parse(ActivityJson.text(item.path("name"))).stream())
                .toList();
        featuredTags.replaceRemote(actor.id(), names);
        log.debug("Read {} featured tags of {}", names.size(), actor.uri());
    }

    /**
     * @return the document, or empty if the actor's server refuses to serve it
     * @throws IllegalStateException if the server couldn't be reached, which is worth trying again
     */
    private Optional<JsonNode> fetch(URI url) {
        try {
            return Optional.of(documents.fetch(url));
        } catch (DocumentFetchException e) {
            if (e.isTemporary()) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            log.debug("Not reading featured tags from {}, which couldn't be fetched: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
