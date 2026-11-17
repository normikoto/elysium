package dev.biribiri.elysium.activitypub.inbound;

import dev.biribiri.elysium.actor.AccountMigrations;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.graph.FollowMoves;
import dev.biribiri.elysium.graph.FollowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * Takes an account's {@code Move} to another, as FEP-7628 and Mastodon send it: its {@code object} is its {@code actor},
 * and its {@code target} is the new account. That's checked by {@link MoveChecks}, which then moves the account's
 * followers here. A {@code Move} that names another {@code object} would be another server speaking for that account, and
 * is ignored, as Mastodon does.
 *
 * <p>The new account is fetched on the old one's word, so that's only done when the move would change something here:
 * when a local account follows, blocks or mutes the old one. Otherwise the old account's document is only fetched again,
 * for its {@code movedTo}.
 */
@Component
class MoveHandler implements InboundActivityHandler {

    private static final Logger log = LoggerFactory.getLogger(MoveHandler.class);

    private final ActorService actors;
    private final FollowService follows;
    private final FollowMoves followMoves;
    private final AccountMigrations migrations;
    private final MoveChecks checks;
    private final ActorRefetch refetch;
    private final JsonMapper json;

    MoveHandler(
            ActorService actors,
            FollowService follows,
            FollowMoves followMoves,
            AccountMigrations migrations,
            MoveChecks checks,
            ActorRefetch refetch,
            JsonMapper json) {
        this.actors = actors;
        this.follows = follows;
        this.followMoves = followMoves;
        this.migrations = migrations;
        this.checks = checks;
        this.refetch = refetch;
        this.json = json;
    }

    @Override
    public String type() {
        return "Move";
    }

    @Override
    public void handle(InboundActivity activity) {
        var body = json.readTree(activity.body());
        var actorUri = URI.create(activity.actorUri());
        var object = ActivityObject.uriOf(body.path("object"));
        if (object.filter(actorUri::equals).isEmpty()) {
            log.debug("Ignoring Move {}, whose object isn't its actor", activity.id());
            return;
        }
        var target = ActivityObject.uriOf(body.path("target"))
                .filter(uri -> "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null)
                .filter(uri -> InboundActivity.isStorableId(uri.toString()))
                .filter(uri -> !uri.equals(actorUri));
        if (target.isEmpty()) {
            log.debug("Ignoring Move {}, which doesn't have a target that can be used", activity.id());
            return;
        }
        var actor = actors.findByUri(actorUri).filter(Actor::isActive).filter(found -> !actors.isLocal(found));
        if (actor.isEmpty()) {
            return;
        }
        if (!follows.hasLocalFollowers(actorUri) && !followMoves.hasLocalBlocksOrMutes(actor.get().id())) {
            refetch.queue(actorUri, activity.id());
            return;
        }
        if (migrations.recentlyMoved(actor.get().id())) {
            log.debug("Ignoring Move {}, since {} moved too recently", activity.id(), actorUri);
            return;
        }
        checks.queue(activity.id(), actorUri, target.get());
    }
}
