package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@code Link} header that points to the pages around a page of results.
 */
final class PageLinks {

    private PageLinks() {
    }

    /**
     * Adds the header, if there's anything to link to. Only a full page can have older results after it. Any non-empty page
     * offers a link to newer ones, which clients use to poll for new results.
     *
     * @param filters the request's parameters to repeat in those links, leaving out the page cursors
     * @param found   the IDs of the results on this page, newest first
     */
    static void addTo(
            ResponseEntity.BodyBuilder response,
            InstanceProperties instance,
            String path,
            Map<String, ?> filters,
            int limit,
            List<UUID> found) {
        var base = UriComponentsBuilder.fromUri(instance.resolve(path)).queryParam("limit", limit);
        filters.forEach((name, value) -> {
            if (value instanceof java.util.Collection<?> values) {
                base.queryParam(name, values);
            } else {
                base.queryParam(name, value);
            }
        });
        var links = new ArrayList<String>();
        if (found.size() == limit) {
            links.add(link(base, "max_id", found.getLast(), "next"));
        }
        if (!found.isEmpty()) {
            links.add(link(base, "min_id", found.getFirst(), "prev"));
        }
        if (!links.isEmpty()) {
            response.header(HttpHeaders.LINK, String.join(", ", links));
        }
    }

    private static String link(UriComponentsBuilder base, String cursor, UUID id, String relation) {
        var uri = base.cloneBuilder().queryParam(cursor, id).encode().build().toUriString();
        return "<%s>; rel=\"%s\"".formatted(uri, relation);
    }
}
