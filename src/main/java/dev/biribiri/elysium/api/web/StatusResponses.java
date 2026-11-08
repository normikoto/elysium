package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.BoostService;
import dev.biribiri.elysium.content.BoostStats;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.LikeService;
import dev.biribiri.elysium.content.LikeStats;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import dev.biribiri.elysium.content.StatusVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final BoostService boosts;

    StatusResponses(
            ActorService actors, AccountResponses accounts, InstanceProperties instance,
            StatusService statuses, LikeService likes, BoostService boosts) {
        this.likes = likes;
        this.boosts = boosts;
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

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    List<StatusEditResponse> history(Status status, List<StatusVersion> versions) {
        var account = actors.findById(status.actorId())
                .filter(Actor::isActive)
                .map(accounts::one)
                .orElseThrow(RecordNotFoundException::new);
        return versions.stream().map(version -> StatusEditResponse.from(version, account)).toList();
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
     * statuses were read, so that one of them doesn't fail the whole response. A boost is left out the same way when the
     * status it boosts can't be shown to the viewer.
     */
    private List<StatusResponse> build(List<Status> found, boolean includeText, Optional<UUID> viewer) {
        var originalIds = found.stream().filter(Status::isReblog).map(Status::reblogOfId).distinct().toList();
        var originals = (viewer.isPresent()
                ? statuses.findVisible(originalIds, viewer.get())
                : statuses.findVisibleToAnyone(originalIds)).stream()
                .collect(Collectors.toMap(Status::id, Function.identity()));
        var all = Stream.concat(found.stream(), originals.values().stream()).toList();

        var authors = actors.findByIds(all.stream().map(Status::actorId).collect(Collectors.toSet())).stream()
                .filter(Actor::isActive)
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var accountResponses = accounts.byId(authors.values());
        var ids = all.stream().map(Status::id).distinct().toList();
        var mentions = statuses.findMentions(ids);
        var likeStats = likes.stats(ids, viewer);
        var boostStats = boosts.stats(ids, viewer);
        BiFunction<Status, StatusResponse, StatusResponse> response = (status, reblog) -> StatusResponse.from(
                status,
                accountResponses.get(status.actorId()),
                mentions.getOrDefault(status.id(), List.of()).stream()
                        .map(actor -> MentionResponse.from(actor, actors.isLocal(actor)))
                        .toList(),
                includeText,
                status.isReblog() ? LikeStats.EMPTY : likeStats.getOrDefault(status.id(), LikeStats.EMPTY),
                status.isReblog() ? BoostStats.EMPTY : boostStats.getOrDefault(status.id(), BoostStats.EMPTY),
                reblog);
        return found.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .flatMap(status -> {
                    if (!status.isReblog()) {
                        return Stream.of(response.apply(status, null));
                    }
                    var original = originals.get(status.reblogOfId());
                    if (original == null || !authors.containsKey(original.actorId())) {
                        return Stream.empty();
                    }
                    return Stream.of(response.apply(status, response.apply(original, null)));
                })
                .toList();
    }
}
