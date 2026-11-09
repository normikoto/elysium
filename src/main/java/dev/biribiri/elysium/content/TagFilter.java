package dev.biribiri.elysium.content;

import dev.biribiri.elysium.text.Hashtags;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Which hashtags a listed status has to have, by their normalized names.
 *
 * @param any  it has at least one of these, which can't be empty
 * @param all  it has every one of these
 * @param none it has none of these
 */
public record TagFilter(Set<String> any, Set<String> all, Set<String> none) {

    public TagFilter {
        any = Set.copyOf(any);
        all = Set.copyOf(all);
        none = Set.copyOf(none);
        if (any.isEmpty()) {
            throw new IllegalArgumentException("A tag filter needs at least one tag to look for");
        }
    }

    public static TagFilter of(String name) {
        return new TagFilter(Set.of(name), Set.of(), Set.of());
    }

    /**
     * Reads a filter from names as a request gives them. Names that aren't tags are ignored, except in {@code all},
     * where one can't match anything.
     *
     * @param name a status has this tag or one in {@code any}
     * @return the filter, or empty if no status can match it
     */
    public static Optional<TagFilter> parse(
            String name, Collection<String> any, Collection<String> all, Collection<String> none) {
        var parsed = Hashtags.parse(name);
        if (parsed.isEmpty() || all.stream().anyMatch(candidate -> Hashtags.parse(candidate).isEmpty())) {
            return Optional.empty();
        }
        var anyTags = Hashtags.parseAll(any);
        anyTags.addFirst(parsed.get());
        return Optional.of(new TagFilter(anyTags, Hashtags.parseAll(all), Hashtags.parseAll(none)));
    }
}
