package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.moderation.DomainBlocks;
import dev.biribiri.elysium.moderation.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores another server's report of local accounts, as one report for each. Servers usually send these as their instance
 * actor, to keep the reporter anonymous, but nothing here depends on who sent it beyond the inbox's checks of the
 * signature. Only what's named in {@code object} and stored here as local is used: nothing is fetched for it, so a
 * {@code Flag} can't make this server request anything but its actor, and a URI here that doesn't exist is ignored.
 */
@Component
class FlagHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(FlagHandler.class);

    /**
     * Mastodon sends the account and the statuses its user picked, which is far fewer than this.
     */
    static final int MAX_OBJECTS = Reports.MAX_STATUSES + 10;

    private final ActorService actors;
    private final RemoteActors remoteActors;
    private final LikeService likes;
    private final StatusService statuses;
    private final Reports reports;
    private final DomainBlocks domainBlocks;
    private final InstanceProperties instance;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    FlagHandler(ActorService actors, RemoteActors remoteActors, LikeService likes, StatusService statuses, Reports reports,
                DomainBlocks domainBlocks, InstanceProperties instance, TransactionTemplate transaction, JsonMapper json) {
        this.actors = actors;
        this.remoteActors = remoteActors;
        this.likes = likes;
        this.statuses = statuses;
        this.reports = reports;
        this.domainBlocks = domainBlocks;
        this.instance = instance;
        this.transaction = transaction;
        this.json = json;
    }

    @Override
    public String type() {
        return "Flag";
    }

    @Override
    public void handle(InboundActivity activity) {
        var document = json.readTree(activity.body());
        var local = ActivityJson.ids(document.path("object")).stream()
                .limit(MAX_OBJECTS)
                .map(ActivityObject::parseUri)
                .flatMap(Optional::stream)
                .filter(uri -> RemoteActorDocument.isOnDomain(uri, instance.domain()))
                .toList();
        var targets = targets(local);
        if (targets.isEmpty()) {
            log.debug("Ignoring Flag {}, which isn't of anything here", activity.id());
            return;
        }
        var comment = ActivityJson.naturalText(document, "content");
        // Before the transaction, because this can make a request to another server.
        var reporter = remoteActors.fetchOrRefresh(URI.create(activity.actorUri()));
        transaction.executeWithoutResult(status -> {
            // Fetching the actor can take a while, and its server can have been blocked since the job started.
            if (domainBlocks.isBlocked(reporter.uri().getHost()) || actors.isInactive(reporter.uri())) {
                return;
            }
            targets.forEach((target, statusIds) ->
                    reports.receive(reporter.id(), target, statusIds, comment, activity.id()));
        });
    }

    /**
     * The local accounts that are named, or whose statuses are, each with its statuses. A Flag of statuses alone is about
     * their authors, which is how some servers send them.
     */
    private LinkedHashMap<UUID, List<UUID>> targets(List<URI> uris) {
        var targets = new LinkedHashMap<UUID, List<UUID>>();
        var statusIds = new LinkedHashSet<UUID>();
        for (var uri : uris) {
            var actor = actors.findLocalByUri(uri);
            if (actor.isPresent()) {
                targets.computeIfAbsent(actor.get().id(), id -> new ArrayList<>());
            } else {
                likes.localStatusId(uri).ifPresent(statusIds::add);
            }
        }
        for (Status status : statuses.findForReport(statusIds)) {
            if (actors.findLocalById(status.actorId()).filter(Actor::isActive).isPresent()) {
                targets.computeIfAbsent(status.actorId(), id -> new ArrayList<>()).add(status.id());
            }
        }
        return targets;
    }
}
