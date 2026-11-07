package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Builds the {@code Note} for a status, so that one that's fetched matches one that's delivered.
 */
@Service
public class Notes {

    private final StatusService statuses;
    private final ActorService actors;

    Notes(StatusService statuses, ActorService actors) {
        this.statuses = statuses;
        this.actors = actors;
    }

    /**
     * @throws IllegalArgumentException if the status is direct
     */
    public Note of(Status status, Actor author) {
        var parent = shownParent(status);
        return Note.from(
                status,
                author,
                parent.map(Status::uri).orElse(null),
                parent.flatMap(shown -> actors.findById(shown.actorId())).filter(Actor::isActive).map(Actor::uri).orElse(null));
    }

    /**
     * A parent that everyone can see. A followers-only reply can also show its author's own followers-only parent, since
     * the same people get both, which keeps these threads together. Looking the parent up as the author means that anything
     * else found this way is the author's own.
     */
    private Optional<Status> shownParent(Status status) {
        return Optional.ofNullable(status.inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, Optional.of(status.actorId())))
                .filter(parent -> parent.isVisibleTo(Optional.empty())
                        || (status.visibility() == Visibility.PRIVATE && parent.visibility() == Visibility.PRIVATE));
    }
}
