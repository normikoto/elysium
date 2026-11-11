package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds statuses for admin pages, so that each page shows them the same way.
 */
@Service
public class ModeratedStatuses {

    private final ActorService actors;
    private final MediaAttachments attachments;

    ModeratedStatuses(ActorService actors, MediaAttachments attachments) {
        this.actors = actors;
        this.attachments = attachments;
    }

    /**
     * @param showAll whether to show the content of followers-only and direct statuses, which only reports do, since whoever
     *                reported one shared it with admins on purpose. Otherwise admins see only what anyone could.
     * @return those whose author can be loaded, in the same order
     */
    public List<ModeratedStatus> rows(List<Status> statuses, boolean showAll) {
        var authors = actors.findByIds(statuses.stream().map(Status::actorId).distinct().toList()).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var shownIds = statuses.stream()
                .filter(status -> showAll || status.isVisibleTo(Optional.empty()))
                .map(Status::id)
                .toList();
        var media = attachments.findByStatuses(shownIds);
        return statuses.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .map(status -> row(status, authors.get(status.actorId()), shownIds.contains(status.id()), media))
                .toList();
    }

    private ModeratedStatus row(Status status, Actor author, boolean shown, Map<UUID, List<MediaAttachment>> media) {
        var local = actors.isLocal(author);
        return new ModeratedStatus(
                status.id(),
                local ? author.username() : author.username() + "@" + author.domain(),
                local,
                author.isSuspended(),
                status.visibility().value(),
                status.publishedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                status.url() != null ? status.url() : status.uri(),
                shown,
                shown ? status.spoilerText() : "",
                shown ? status.content() : "",
                media.getOrDefault(status.id(), List.of()).stream()
                        .map(attachment -> attachment.description() == null ? "" : attachment.description())
                        .toList());
    }
}
