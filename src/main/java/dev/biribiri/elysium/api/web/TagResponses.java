package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.StatusService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds tag responses, loading every tag's history in one query.
 */
@Component
class TagResponses {

    /**
     * How many days of use each tag shows, as on Mastodon.
     */
    static final int HISTORY_DAYS = 7;

    private final StatusService statuses;
    private final InstanceProperties instance;

    TagResponses(StatusService statuses, InstanceProperties instance) {
        this.statuses = statuses;
        this.instance = instance;
    }

    /**
     * @param names    normalized names
     * @param signedIn whether there's a user to say if they follow each tag
     */
    List<TagResponse> build(List<String> names, boolean signedIn) {
        var history = statuses.findTagHistory(names, HISTORY_DAYS);
        return names.stream()
                .map(name -> TagResponse.of(name, history.getOrDefault(name, List.of()), signedIn, instance))
                .toList();
    }
}
