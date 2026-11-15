package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.inbox.InboxService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Renders the entities that streams send exactly as the REST API does, so that a client can treat a streamed status like
 * one it fetched. Each is rendered for one viewer, since what it says about favourites, boosts and pins is theirs.
 * Empty means the viewer can't see it, or it's gone.
 */
@Component
public class StreamPayloads {

    private final StatusService statuses;
    private final StatusResponses statusResponses;
    private final InboxService inbox;
    private final InboxResponses inboxResponses;

    StreamPayloads(StatusService statuses, StatusResponses statusResponses, InboxService inbox,
                   InboxResponses inboxResponses) {
        this.statuses = statuses;
        this.statusResponses = statusResponses;
        this.inbox = inbox;
        this.inboxResponses = inboxResponses;
    }

    /**
     * A status, and the contexts in which the viewer's filters hide it, whose streams mustn't send it.
     */
    public record StreamedStatus(Object payload, Set<PostFilter.Context> hiddenIn) {
    }

    public Optional<StreamedStatus> status(UUID statusId, Optional<UUID> viewer) {
        return statuses.findVisible(statusId, viewer)
                .flatMap(status -> statusResponses.streamed(status, viewer));
    }

    public Optional<Object> notification(UUID notificationId, UUID viewer) {
        return inbox.notification(viewer, notificationId)
                .flatMap(notification -> first(inboxResponses.notifications(viewer, List.of(notification))));
    }

    public Optional<Object> conversation(UUID conversationId, UUID viewer) {
        return inbox.conversation(viewer, conversationId)
                .flatMap(conversation -> first(inboxResponses.conversations(viewer, List.of(conversation))));
    }

    private static Optional<Object> first(List<?> rendered) {
        return rendered.isEmpty() ? Optional.empty() : Optional.of(rendered.getFirst());
    }
}
