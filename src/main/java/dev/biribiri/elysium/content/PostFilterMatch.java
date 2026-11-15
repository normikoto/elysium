package dev.biribiri.elysium.content;

import java.util.List;
import java.util.UUID;

/**
 * A filter that a status matches.
 *
 * @param keywordMatches the text that its keywords matched, as the status has it
 * @param statusMatches  the filter's statuses that it matched, which can be the one a boost boosts
 */
public record PostFilterMatch(PostFilter filter, List<String> keywordMatches, List<UUID> statusMatches) {

    public PostFilterMatch {
        keywordMatches = List.copyOf(keywordMatches);
        statusMatches = List.copyOf(statusMatches);
    }

    /**
     * Whether the status is left out of lists in this context.
     */
    public boolean hidesIn(PostFilter.Context context) {
        return filter.action() == PostFilter.Action.HIDE && filter.contexts().contains(context);
    }
}
