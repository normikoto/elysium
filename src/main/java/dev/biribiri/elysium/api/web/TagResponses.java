package dev.biribiri.elysium.api.web;

import dev.biribiri.elysium.actor.InstanceProperties;
import dev.biribiri.elysium.content.FeaturedTags;
import dev.biribiri.elysium.content.StatusSearch;
import dev.biribiri.elysium.content.Trends;
import dev.biribiri.elysium.graph.TagFollows;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds tag responses, loading every tag's history in one query, and which of them the viewer follows and features in
 * others.
 */
@Component
class TagResponses {

    /**
     * How many days of use each tag shows, as on Mastodon.
     */
    static final int HISTORY_DAYS = 7;

    private final StatusSearch statuses;
    private final TagFollows follows;
    private final FeaturedTags featured;
    private final InstanceProperties instance;

    TagResponses(StatusSearch statuses, TagFollows follows, FeaturedTags featured, InstanceProperties instance) {
        this.statuses = statuses;
        this.follows = follows;
        this.featured = featured;
        this.instance = instance;
    }

    /**
     * @param names  normalized names
     * @param viewer the user to say if they follow and feature each tag, if there is one
     */
    List<TagResponse> build(List<String> names, Optional<UUID> viewer) {
        var history = statuses.findTagHistory(names, HISTORY_DAYS);
        return build(names, viewer, name -> history.getOrDefault(name, List.of()).stream()
                .map(TagResponse.History::from).toList());
    }

    List<TagResponse> trending(List<Trends.Sharing> tags, Optional<UUID> viewer) {
        var history = tags.stream().collect(Collectors.toMap(Trends.Sharing::value, TagResponses::trendHistory));
        return build(tags.stream().map(Trends.Sharing::value).toList(), viewer, history::get);
    }

    static List<TagResponse.History> trendHistory(Trends.Sharing sharing) {
        return sharing.history().stream().map(day -> TagResponse.History.of(day.date(), day.uses(), day.accounts()))
                .toList();
    }

    private List<TagResponse> build(List<String> names, Optional<UUID> viewer,
                                    Function<String, List<TagResponse.History>> history) {
        var followed = viewer.map(id -> follows.followed(id, names));
        var featuring = viewer.map(id -> featured.featured(id, names));
        return names.stream()
                .map(name -> TagResponse.of(name, history.apply(name),
                        followed.map(set -> set.contains(name)).orElse(null),
                        featuring.map(set -> set.contains(name)).orElse(null), instance))
                .toList();
    }
}
