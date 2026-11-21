package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.content.internal.BookmarkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Statuses that local accounts saved for themselves. Only the account that made a bookmark ever learns of it. */
@Service
public class BookmarkService {
    private final BookmarkRepository bookmarks;
    private final StatusService statuses;
    private final ActorService actors;
    private final StatusHolds holds;

    BookmarkService(BookmarkRepository bookmarks, StatusService statuses, ActorService actors, StatusHolds holds) {
        this.bookmarks = bookmarks;
        this.statuses = statuses;
        this.actors = actors;
        this.holds = holds;
    }

    /**
     * @return the status, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> bookmark(UUID statusId, UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) return Optional.empty();
        // Held, so that the status can't be deleted before the insert, which would then fail on its foreign key.
        var found = holds.holdVisible(statusId, actorId);
        found.ifPresent(status -> bookmarks.insert(actorId, status.id()));
        return found;
    }

    /**
     * Removes the bookmark even when the account can no longer see the status, as on Mastodon, since it would otherwise
     * stay with no way to clear it.
     *
     * @return the status, or empty if the account can't see it
     */
    @Transactional
    public Optional<Status> unbookmark(UUID statusId, UUID actorId) {
        if (actors.findLocalById(actorId).isEmpty()) return Optional.empty();
        bookmarks.delete(actorId, statusId);
        return statuses.findVisible(statusId, Optional.of(actorId));
    }

    public Set<UUID> bookmarkedIds(Collection<UUID> statusIds, Optional<UUID> viewer) {
        return viewer.map(id -> bookmarks.bookmarkedAmong(id, statusIds)).orElse(Set.of());
    }

    public BookmarkPage page(UUID actorId, IdPage page) {
        var rows = bookmarks.page(actorId, page);
        var visible = statuses.findVisible(rows.stream().map(BookmarkRepository.Row::statusId).toList(), actorId).stream()
                .collect(Collectors.toMap(Status::id, Function.identity()));
        return new BookmarkPage(
                rows.stream().map(BookmarkRepository.Row::id).toList(),
                rows.stream().map(row -> visible.get(row.statusId())).filter(Objects::nonNull).toList());
    }

    public void removeForActor(UUID actorId) {
        bookmarks.removeForActor(actorId);
    }
}
