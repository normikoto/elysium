package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

    StatusResponses(ActorService actors, AccountResponses accounts, InstanceProperties instance) {
        this.actors = actors;
        this.accounts = accounts;
        this.instance = instance;
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse one(Status status) {
        return only(build(List.of(status), false));
    }

    /**
     * @throws RecordNotFoundException if the status's author can't be loaded
     */
    StatusResponse oneWithText(Status status) {
        return only(build(List.of(status), true));
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
            String path, Map<String, String> filters, StatusPage page, List<Status> found) {
        var response = ResponseEntity.ok();
        PageLinks.addTo(response, instance, path, filters, page.limit(), found.stream().map(Status::id).toList());
        return response.body(build(found, false));
    }

    /**
     * Leaves out statuses whose author can't be loaded, such as when the account was deleted after the statuses were
     * read, so that one of them doesn't fail the whole response.
     */
    private List<StatusResponse> build(List<Status> found, boolean includeText) {
        var actorIds = found.stream().map(Status::actorId).collect(Collectors.toSet());
        var authors = actors.findLocalByIds(actorIds).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var accountResponses = accounts.build(List.copyOf(authors.values())).stream()
                .collect(Collectors.toMap(account -> UUID.fromString(account.id()), Function.identity()));
        return found.stream()
                .filter(status -> authors.containsKey(status.actorId()))
                .map(status -> StatusResponse.from(status, accountResponses.get(status.actorId()), includeText))
                .toList();
    }
}
