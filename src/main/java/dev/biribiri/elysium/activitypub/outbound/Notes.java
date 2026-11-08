package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.vocab.Mention;
import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.Visibility;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public Note of(Status status, Actor author) {
        return of(status, author, mentioned(status));
    }

    /**
     * @param mentioned the accounts {@link StatusService#findMentions} gives, for a caller that has looked them up already
     */
    public Note of(Status status, Actor author, List<Actor> mentioned) {
        var parent = shownParent(status, mentioned);
        return Note.from(
                status,
                author,
                parent.map(Status::uri).orElse(null),
                parent.flatMap(shown -> actors.findById(shown.actorId())).filter(Actor::isActive).map(Actor::uri).orElse(null),
                mentioned.stream().map(actor -> Mention.of(actor.uri(), "@" + actor.username() + "@" + actor.domain())).toList());
    }

    /**
     * The accounts a status mentions, which its {@code Note} is addressed to as well. Only the accounts that are still
     * active are named, as Mastodon's API shows them, so a later fetch can name fewer than were sent the status.
     */
    List<Actor> mentioned(Status status) {
        return statuses.findMentions(List.of(status.id())).getOrDefault(status.id(), List.of());
    }

    /**
     * A parent that everyone can see. A followers-only reply can also show its author's own followers-only parent, since
     * the same people get both, which keeps these threads together. Another account's followers-only status that the author
     * can see, as a follower or because it mentions them, isn't shown, since the reply's audience didn't get it.
     *
     * <p>A direct reply can show a followers-only or direct parent that its author could see, if it mentions the parent's
     * author, which is how a direct conversation goes on. Without that, other servers would show each reply as a new
     * conversation. Those it mentions learn that the parent exists, as they do on Mastodon.
     */
    private Optional<Status> shownParent(Status status, List<Actor> mentioned) {
        return Optional.ofNullable(status.inReplyToId())
                .flatMap(parentId -> statuses.findVisible(parentId, Optional.of(status.actorId())))
                .filter(parent -> parent.isVisibleTo(Optional.empty())
                        || (status.visibility() == Visibility.PRIVATE && parent.visibility() == Visibility.PRIVATE
                        && parent.actorId().equals(status.actorId()))
                        || (status.visibility() == Visibility.DIRECT
                        && mentioned.stream().anyMatch(actor -> actor.id().equals(parent.actorId()))));
    }
}
