package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.LikeStats;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds status responses, loading the accounts they embed together rather than one query per status.
 */
@Component
class StatusResponses {

    private final ActorService actors;
    private final AccountResponses accounts;
    private final InstanceProperties instance;
    private final StatusService statuses;
    private final LikeService likes;

    StatusResponses(
            ActorService actors, AccountResponses accounts, InstanceProperties instance,
            StatusService statuses, LikeService likes) {
        this.likes = likes;
        this.actors = actors;
        this.statuses = statuses;
        this.accounts = accounts;
        this.instance = instance;
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse one(Status status, Optional<UUID> viewer) {
        return only(build(List.of(status), false, viewer));
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse oneWithText(Status status, Optional<UUID> viewer) {
        return only(build(List.of(status), true, viewer));
    }

    /**
     * Leaves out statuses whose author can't be loaded, like {@link #page}.
     */
    List<StatusResponse> list(List<Status> found, Optional<UUID> viewer) {
        return build(found, false, viewer);
    }

    private static StatusResponse only(List<StatusResponse> built) {
        return built.stream().findFirst().orElseThrow(RecordNotFoundException::new);
    }

    /**
     * Builds a page of statuses, with a {@code Link} header pointing to the pages around it. The links come from every
     * status found, so paging still works if {@link #build} leaves some out.
     *
     * @param filters the request's parameters to repeat in those links, leaving out the page cursors
     */
    ResponseEntity<List<StatusResponse>> page(
            String path, Map<String, String> filters, StatusPage page, List<Status> found, Optional<UUID> viewer) {
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, path, filters, page.limit(), found.stream().map(Status::id).toList());
        return response.body(build(found, false, viewer));
    }

    /**
     * Leaves out statuses whose author can't be loaded, such as when the account was suspended or deleted after the
     * statuses were read, so that one of them doesn't fail the whole response.
     */
    private List<StatusResponse> build(List<Status> found, boolean includeText, Optional<UUID> viewer) {
        var actorIds = found.stream().map(Status::actorId).collect(Collectors.toSet());
        var authors = actors.findByIds(actorIds).stream()
                .filter(Actor::isActive)
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var accountResponses = accounts.byId(authors.values());
        var mentions = statuses.findMentions(found.stream().map(Status::id).toList());
        var stats = likes.stats(found.stream().map(Status::id).toList(), viewer);
        return found.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .map(status -> StatusResponse.from(
                        status,
                        accountResponses.get(status.actorId()),
                        mentions.getOrDefault(status.id(), List.of()).stream()
                                .map(actor -> MentionResponse.from(actor, actors.isLocal(actor)))
                                .toList(),
                        includeText, stats.getOrDefault(status.id(), LikeStats.EMPTY)))
                .toList();
    }
}
