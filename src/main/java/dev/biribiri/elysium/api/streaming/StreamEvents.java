package dev.biribiri.elysium.api.streaming;

import dev.biribiri.elysium.api.web.StreamPayloads;
import dev.biribiri.elysium.content.FiltersChanged;
import dev.biribiri.elysium.content.PostFilter;
import dev.biribiri.elysium.content.RemoteStatusDeleted;
import dev.biribiri.elysium.content.RemoteStatusEdited;
import dev.biribiri.elysium.content.StatusDeleted;
import dev.biribiri.elysium.content.StatusEdited;
import dev.biribiri.elysium.content.StatusReblogged;
import dev.biribiri.elysium.content.StatusStored;
import dev.biribiri.elysium.content.StatusUnreblogged;
import dev.biribiri.elysium.content.TagFilter;
import dev.biribiri.elysium.notification.ConversationUpdated;
import dev.biribiri.elysium.notification.NotificationAdded;
import dev.biribiri.elysium.timeline.TimelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Tells the open streams about what's happened, once it's been saved.
 * <p>
 * A stream only gets a status its timeline would list for that viewer, decided by the timeline's own query, so the two
 * can't disagree. Nothing here may throw: a listener that fails runs again, which would send everything it had sent again.
 * Connections are only in memory, so a run after a restart has nobody to tell, and a repeat that does reach someone is
 * something Mastodon's streams can send too.
 */
@Component
class StreamEvents {

    private static final Logger log = LoggerFactory.getLogger(StreamEvents.class);

    private final StreamRegistry registry;
    private final TimelineService timelines;
    private final StreamPayloads payloads;
    private final JsonMapper json;

    StreamEvents(StreamRegistry registry, TimelineService timelines, StreamPayloads payloads, JsonMapper json) {
        this.registry = registry;
        this.timelines = timelines;
        this.payloads = payloads;
        this.json = json;
    }

    /**
     * A backfilled status is old, so it isn't new to anyone's timeline.
     */
    @ApplicationModuleListener
    void on(StatusStored event) {
        if (event.backfilled()) return;
        safely(() -> status(event.statusId(), "update"));
    }

    @ApplicationModuleListener
    void on(StatusReblogged event) {
        safely(() -> status(event.boostId(), "update"));
    }

    @ApplicationModuleListener
    void on(StatusEdited event) {
        safely(() -> status(event.statusId(), "status.update"));
    }

    @ApplicationModuleListener
    void on(RemoteStatusEdited event) {
        safely(() -> status(event.statusId(), "status.update"));
    }

    @ApplicationModuleListener
    void on(StatusDeleted event) {
        safely(() -> deleted(event.statusId()));
    }

    @ApplicationModuleListener
    void on(StatusUnreblogged event) {
        safely(() -> deleted(event.boostId()));
    }

    @ApplicationModuleListener
    void on(RemoteStatusDeleted event) {
        safely(() -> deleted(event.statusId()));
    }

    /**
     * The {@code user} stream only carries notifications for a token that could list them.
     */
    @ApplicationModuleListener
    void on(NotificationAdded event) {
        safely(() -> {
            var payload = lazy(() -> payloads.notification(event.notificationId(), event.recipientId()));
            for (var connection : registry.connections()) {
                if (!connection.viewer().equals(Optional.of(event.recipientId()))
                        || !connection.scopes().contains("read:notifications")) continue;
                for (var key : connection.subscriptions()) {
                    if (key.kind() == StreamKey.Kind.USER || key.kind() == StreamKey.Kind.NOTIFICATIONS) {
                        payload.get().ifPresent(rendered -> connection.send(key, "notification", rendered));
                    }
                }
            }
        });
    }

    /**
     * Mastodon only tells the {@code user} stream, and sends no payload.
     */
    @ApplicationModuleListener
    void on(FiltersChanged event) {
        safely(() -> {
            for (var connection : registry.connections()) {
                if (!connection.viewer().equals(Optional.of(event.accountId()))) continue;
                for (var key : connection.subscriptions()) {
                    if (key.kind() == StreamKey.Kind.USER) {
                        connection.send(key, "filters_changed", null);
                    }
                }
            }
        });
    }

    @ApplicationModuleListener
    void on(ConversationUpdated event) {
        safely(() -> {
            var payload = lazy(() -> payloads.conversation(event.conversationId(), event.ownerId()));
            for (var connection : registry.connections()) {
                if (!connection.viewer().equals(Optional.of(event.ownerId()))) continue;
                for (var key : connection.subscriptions()) {
                    if (key.kind() == StreamKey.Kind.DIRECT) {
                        payload.get().ifPresent(rendered -> connection.send(key, "conversation", rendered));
                    }
                }
            }
        });
    }

    private void status(UUID statusId, String event) {
        var connections = registry.connections();
        if (connections.isEmpty()) return;
        var homeCandidates = connections.stream()
                .filter(connection -> connection.subscriptions().stream().anyMatch(key -> key.kind() == StreamKey.Kind.USER))
                .flatMap(connection -> connection.viewer().stream())
                .collect(Collectors.toSet());
        var home = homeCandidates.isEmpty() ? Set.<UUID>of() : new HashSet<>(timelines.homeViewers(statusId, homeCandidates));
        // Each list was checked to be its viewer's when it was subscribed to.
        var listIds = connections.stream()
                .filter(connection -> connection.viewer().isPresent())
                .flatMap(connection -> connection.subscriptions().stream())
                .filter(key -> key.kind() == StreamKey.Kind.LIST)
                .map(StreamKey::listId)
                .collect(Collectors.toSet());
        var lists = timelines.listsShowing(statusId, listIds);
        // Whether anyone at all could see it there decides most connections with one query, before each viewer's own.
        var anyone = new HashMap<StreamKey, Boolean>();
        var viewers = new HashMap<ViewerKey, Boolean>();
        var rendered = new HashMap<Optional<UUID>, Optional<Rendered>>();

        for (var connection : connections) {
            var viewer = connection.viewer();
            for (var key : connection.subscriptions()) {
                var listed = switch (key.kind()) {
                    case USER -> viewer.isPresent() && home.contains(viewer.get());
                    case LIST -> viewer.isPresent() && lists.contains(key.listId());
                    case PUBLIC, HASHTAG -> anyone.computeIfAbsent(key, k -> listed(statusId, k, Optional.empty()))
                            && (viewer.isEmpty() || viewers.computeIfAbsent(new ViewerKey(key, viewer.get()),
                            k -> listed(statusId, key, viewer)));
                    case NOTIFICATIONS, DIRECT -> false;
                };
                if (listed) {
                    rendered.computeIfAbsent(viewer, v -> payloads.status(statusId, v)
                                    .map(status -> new Rendered(write(status.payload()), status.hiddenIn())))
                            .ifPresent(status -> {
                                if (!status.hiddenIn().contains(context(key))) {
                                    connection.send(key, event, status.payload());
                                } else if (event.equals("status.update")) {
                                    // An edit can make a status the client already has match, and it can't be left
                                    // showing the old text.
                                    connection.send(key, "delete", statusId.toString());
                                }
                            });
                }
            }
        }
    }

    private record ViewerKey(StreamKey key, UUID viewer) {
    }

    private record Rendered(String payload, Set<PostFilter.Context> hiddenIn) {
    }

    /**
     * The filter context of the timeline that a stream of statuses follows. Mastodon's {@code home} covers lists.
     */
    private static PostFilter.Context context(StreamKey key) {
        return key.kind() == StreamKey.Kind.USER || key.kind() == StreamKey.Kind.LIST
                ? PostFilter.Context.HOME : PostFilter.Context.PUBLIC;
    }

    private boolean listed(UUID statusId, StreamKey key, Optional<UUID> viewer) {
        return switch (key.kind()) {
            case PUBLIC -> timelines.inPublic(statusId, key.origin(), key.onlyMedia(), viewer);
            case HASHTAG -> timelines.inTag(statusId, TagFilter.of(key.tag()), key.origin(), false, viewer);
            default -> false;
        };
    }

    /**
     * Every stream that carries statuses is told, as on Mastodon, since an ID that a client doesn't have tells it nothing,
     * and what was listed can't be worked out once the status is gone.
     */
    private void deleted(UUID statusId) {
        var payload = statusId.toString();
        for (var connection : registry.connections()) {
            for (var key : connection.subscriptions()) {
                if (key.kind().hasStatuses()) {
                    connection.send(key, "delete", payload);
                }
            }
        }
    }

    private Supplier<Optional<String>> lazy(Supplier<Optional<Object>> render) {
        var cache = new Object() {
            Optional<String> value;
        };
        return () -> {
            if (cache.value == null) {
                cache.value = render.get().map(this::write);
            }
            return cache.value;
        };
    }

    private String write(Object entity) {
        return json.writeValueAsString(entity);
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Couldn't tell the open streams about an event", e);
        }
    }
}
