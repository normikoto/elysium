package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.Actor;
import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.Status;
import dev.biribiri.elysium.content.StatusPage;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
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
    private final StatusService statuses;
    private final InstanceProperties instance;

    StatusResponses(ActorService actors, StatusService statuses, InstanceProperties instance) {
        this.actors = actors;
        this.statuses = statuses;
        this.instance = instance;
    }

    StatusResponse one(Status status) {
        return build(List.of(status), false).getFirst();
    }

    StatusResponse oneWithText(Status status) {
        return build(List.of(status), true).getFirst();
    }

    /**
     * Builds a page of statuses, with a {@code Link} header pointing to the pages around it.
     *
     * @param filters the request's parameters to repeat in those links, leaving out the page cursors
     */
    ResponseEntity<List<StatusResponse>> page(
            String path, Map<String, String> filters, StatusPage page, List<Status> found) {
        var response = ResponseEntity.ok();
        var links = links(path, filters, page, found);
        if (!links.isEmpty()) {
            response.header(HttpHeaders.LINK, String.join(", ", links));
        }
        return response.body(build(found, false));
    }

    private List<StatusResponse> build(List<Status> found, boolean includeText) {
        var actorIds = found.stream().map(Status::actorId).collect(Collectors.toSet());
        var authors = actors.findLocalByIds(actorIds).stream()
                .collect(Collectors.toMap(Actor::id, Function.identity()));
        var stats = statuses.statsFor(actorIds);
        return found.stream()
                .map(status -> StatusResponse.from(
                        status,
                        AccountResponse.from(
                                authors.get(status.actorId()), stats.get(status.actorId()), instance),
                        includeText))
                .toList();
    }

    /**
     * Only a full page can have older statuses after it. Any non-empty page offers a link to newer ones, which clients
     * use to poll for new statuses.
     */
    private List<String> links(String path, Map<String, String> filters, StatusPage page, List<Status> found) {
        var links = new ArrayList<String>();
        if (found.size() == page.limit()) {
            links.add(link(path, filters, page, "max_id", found.getLast().id(), "next"));
        }
        if (!found.isEmpty()) {
            links.add(link(path, filters, page, "min_id", found.getFirst().id(), "prev"));
        }
        return links;
    }

    private String link(
            String path, Map<String, String> filters, StatusPage page, String cursor, UUID id, String relation) {
        var uri = UriComponentsBuilder.fromUri(instance.resolve(path)).queryParam("limit", page.limit());
        filters.forEach(uri::queryParam);
        return "<%s>; rel=\"%s\"".formatted(uri.queryParam(cursor, id).encode().build().toUriString(), relation);
    }
}
