package dev.biribiri.elysium.activitypub.outbound;

import dev.biribiri.elysium.activitypub.vocab.Document;
import dev.biribiri.elysium.activitypub.vocab.Hashtag;
import dev.biribiri.elysium.activitypub.vocab.Mention;
import dev.biribiri.elysium.activitypub.vocab.Note;
import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.MediaAttachment;
import dev.biribiri.elysium.content.MediaAttachments;
import dev.biribiri.elysium.content.QuotePolicy;
import dev.biribiri.elysium.content.QuoteService;
import dev.biribiri.elysium.content.QuoteState;
import dev.biribiri.elysium.content.StatusQuote;
import dev.biribiri.elysium.activitypub.vocab.ActivityStreams;
import dev.biribiri.elysium.activitypub.vocab.Person;
import dev.biribiri.elysium.activitypub.vocab.QuoteAuthorization;
import java.net.URI;
import java.util.UUID;
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
    private final InstanceProperties instance;
    private final MediaAttachments attachments;
    private final QuoteService quotes;

    Notes(StatusService statuses, ActorService actors, InstanceProperties instance, MediaAttachments attachments,
          QuoteService quotes) {
        this.quotes = quotes;
        this.attachments = attachments;
        this.statuses = statuses;
        this.actors = actors;
        this.instance = instance;
    }

    public Note of(Status status, Actor author) {
        return of(status, author, mentioned(status));
    }

    /**
     * @param mentioned the accounts {@link StatusService#findMentions} gives, for a caller that has looked them up already
     */
    public Note of(Status status, Actor author, List<Actor> mentioned) {
        return of(status, author, mentioned, statuses.findTags(List.of(status.id())).getOrDefault(status.id(), List.of()));
    }

    /**
     * @param tags the names {@link StatusService#findTags} gives, for a caller that has looked them up already
     */
    public Note of(Status status, Actor author, List<Actor> mentioned, List<String> tags) {
        return of(status, author, mentioned, tags,
                attachments.findByStatuses(List.of(status.id())).getOrDefault(status.id(), List.of()));
    }

    /**
     * @param media the attachments {@link MediaAttachments#findByStatuses} gives, for a caller that has looked them up
     *              already
     */
    public Note of(Status status, Actor author, List<Actor> mentioned, List<String> tags, List<MediaAttachment> media) {
        var parent = shownParent(status, mentioned);
        return Note.from(
                status,
                author,
                parent.map(Status::uri).orElse(null),
                parent.flatMap(shown -> actors.findById(shown.actorId())).filter(Actor::isActive).map(Actor::uri).orElse(null),
                mentioned.stream().map(actor -> Mention.of(actor.uri(), "@" + actor.username() + "@" + actor.domain())).toList(),
                Hashtag.of(tags, instance),
                media.stream().map(attachment -> Document.of(attachment, instance)).toList(),
                quotes.find(status.id()).map(quote -> quote(status, quote)).orElse(null),
                policy(status, author));
    }

    /**
     * Legacy properties are only sent once a quote is approved, since the servers that read only those can't tell an
     * approved quote from one that isn't, and would show either.
     */
    private Note.Quote quote(Status status, StatusQuote quote) {
        var quotedAuthor = Optional.ofNullable(quote.quotedAccountId()).flatMap(actors::findById);
        var self = status.actorId().equals(quote.quotedAccountId());
        var shown = quote.state() == QuoteState.ACCEPTED && !quote.isOfDeleted();
        URI authorization = null;
        if (shown && !self && !quote.legacy()) {
            authorization = quote.stampId() != null
                    ? quotedAuthor.map(actor -> QuoteAuthorization.idFor(actor.uri(), quote.stampId())).orElse(null)
                    : quote.approvalUri();
        }
        return new Note.Quote(quote.quotedUri(), quotedAuthor.map(Actor::uri).orElse(null), shown, authorization);
    }

    /**
     * Who can quote a status, as Mastodon says it: an empty list would be the same as none in JSON-LD, so a status that
     * only its author can quote names its author.
     */
    private Note.InteractionPolicy policy(Status status, Actor author) {
        var policy = status.visibility() == Visibility.PRIVATE || status.visibility() == Visibility.DIRECT
                ? QuotePolicy.NOBODY
                : Optional.ofNullable(quotes.policies(List.of(status.id())).get(status.id()))
                .map(dev.biribiri.elysium.content.QuoteApproval::policy)
                .orElse(QuotePolicy.NOBODY);
        return Note.InteractionPolicy.automatic(List.of(switch (policy) {
            case PUBLIC -> ActivityStreams.PUBLIC;
            case FOLLOWERS -> Person.followersOf(author).toString();
            case NOBODY -> author.uri().toString();
        }));
    }

    /**
     * The author of the parent that a status's {@code Note} shows, if it's active, who its activities are sent to.
     */
    UUID shownParentAuthor(Status status) {
        return shownParent(status, mentioned(status)).map(Status::actorId).orElse(null);
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
