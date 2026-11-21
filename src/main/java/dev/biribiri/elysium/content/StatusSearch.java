package dev.biribiri.elysium.content;

import dev.biribiri.elysium.actor.IdPage;
import dev.biribiri.elysium.content.internal.StatusRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds statuses by their words, and hashtags by their names and how much they're used.
 */
@Service
public class StatusSearch {

    private final StatusRepository statuses;

    StatusSearch(StatusRepository statuses) {
        this.statuses = statuses;
    }

    /**
     * Finds statuses by their words, as {@link StatusRepository#search} explains. Only a status's text and content warning
     * are searched, as whole words in any language.
     *
     * @param query     words, in quotes for a phrase, with {@code OR} between alternatives and {@code -} before what
     *                  mustn't be there
     * @param accountId only find this account's statuses, if it's given
     * @param offset    how many matches to skip, after the page's cursors
     */
    public List<Status> search(UUID viewer, String query, Optional<UUID> accountId, IdPage page, int offset) {
        return statuses.search(viewer, query, accountId.orElse(null), page, offset);
    }

    /**
     * Finds hashtags that public statuses here use, by the start of their name, most used first.
     *
     * @param prefix a normalized name
     */
    public List<String> searchTags(String prefix, int limit, int offset) {
        return statuses.searchTags(prefix, limit, offset);
    }

    /**
     * How much each hashtag was used on each of the last {@code days} days, today first, counting public statuses stored
     * here. A day it wasn't used has zeros.
     */
    public Map<String, List<TagDay>> findTagHistory(Collection<String> names, int days) {
        var today = LocalDate.now(ZoneOffset.UTC);
        var first = today.minusDays(days - 1L);
        var used = statuses.tagDays(names, first.atStartOfDay(ZoneOffset.UTC).toInstant());
        var history = new HashMap<String, List<TagDay>>();
        for (var name : names) {
            var byDay = used.getOrDefault(name, List.of()).stream()
                    .collect(Collectors.toMap(TagDay::day, Function.identity()));
            history.put(name, Stream.iterate(today, day -> !day.isBefore(first), day -> day.minusDays(1))
                    .map(day -> byDay.getOrDefault(day, new TagDay(day, 0, 0)))
                    .toList());
        }
        return history;
    }
}
